#!/usr/bin/env python3
"""Build tier manifests, relevance tags and tag-filtered manifests.

  t0      copy listed cases, with their imports, into a separate corpus root
  t1      slow or noisy files of a manifest plus a fixed random sample of the rest
  tags    scan manifest files for features that changes target
  select  tagged files plus a fixed random sample of N others
  cost    projected lane-hours per arm of a manifest under a seed policy
"""
import argparse
import json
from pathlib import Path, PurePosixPath
import random
import re
import shlex
import shutil

import curate
import night
import run

# Heuristic patterns over source text with comments removed; they over-approximate.
TAGS = {
    'seq': r'\bSeq\[|\bSeq\(',
    'seq_append': r'\+\+',
    'seq_slice': r'\[[^\[\]\n]*\.\.[^\[\]\n]*\]',  # drop xs[i..], take xs[..j], xs[i..j]
    'seq_index': r'(?:\b[a-z_$][\w$]*|[)\]])\[[^\[\]\n.:=]+\]',  # only kept with seq
    'set': r'\bSet\[|\bSet\(',
    'multiset': r'\bMultiset\[|\bMultiset\(',
    'map': r'\bMap\[',
    'qp': r'\bforall\b[^;{}]{0,400}?::[^;{}]{0,400}?\bacc\(',
    'qp_predicate': r'\bforall\b[^;{}]{0,400}?::[^;{}]{0,400}?\bacc\(\s*\w+\(',
    'wand': r'--\*',
    'prusti_lifetime': r'\b(?:LifetimeToken|DeadLifetimeToken|lifetime_token_perm_amount)\b',
    'adt': r'\badt\b\s+\w+',
    'perm_expr': r'\bperm\(',
}
COMMENT = re.compile(r'//[^\n]*|/\*.*?\*/', re.S)
IMPORT = re.compile(r'^\s*import\s+"([^"]+)"', re.M)


def scan(text):
    text = COMMENT.sub(' ', text)
    found = {tag for tag, pattern in TAGS.items() if re.search(pattern, text, re.M)}
    return sorted(found if 'seq' in found else found - {'seq_index'})


def tags(root, rows):
    return {r['path']: scan((root / r['path']).read_text(errors='replace')) for r in rows}


def sample(rows, count, seed):
    """A fixed random sample: the same rows for the same inputs and seed."""
    rows = sorted(rows, key=lambda r: r['path'])
    return random.Random(seed).sample(rows, min(count, len(rows)))


def select(rows, file_tags, wanted, count, seed):
    tagged = [r for r in rows if set(file_tags.get(r['path'], ())) & wanted]
    rest = [r for r in rows if r not in tagged]
    return sorted(tagged + sample(rest, count, seed), key=lambda r: r['path'])


def t1(rows, profile, slow, count, seed, classes=('noisy', 'flips', 'always-timeout')):
    files = profile['files']

    def interesting(row):
        f = files.get(row['path'], {})
        return (f.get('median_wall_s') or 0) >= slow or f.get('class') in classes

    chosen = [r for r in rows if interesting(r)]
    rest = [r for r in rows if not interesting(r)]
    return sorted(chosen + sample(rest, count, seed), key=lambda r: r['path'])


def parse_spec(text):
    """Lines 'DEST [SOURCE] [-- VERIFIER_ARG...]'; SOURCE defaults to DEST in the corpus."""
    entries = []
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        words = shlex.split(line)
        arguments = words[words.index('--') + 1:] if '--' in words else []
        words = words[:words.index('--')] if '--' in words else words
        if len(words) not in (1, 2):
            raise ValueError('expected DEST [SOURCE] [-- ARG...]: ' + line)
        entries.append((words[0], words[1] if len(words) == 2 else None, arguments))
    return entries


def build_t0(spec, corpus_root, root):
    """Copy each case and the files it imports into root; return manifest rows."""
    rows = []
    for dest, source, arguments in spec:
        relative = PurePosixPath(dest)
        if relative.is_absolute() or '..' in relative.parts or len(relative.parts) < 2:
            raise ValueError('destination must be GROUP/...: ' + dest)
        source = Path(source) if source else corpus_root / dest
        pending = [(source, root / dest)]
        while pending:
            src, dst = pending.pop()
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dst)
            for name in IMPORT.findall(COMMENT.sub(' ', src.read_text(errors='replace'))):
                pending.append(((src.parent / name).resolve(), (dst.parent / name).resolve()))
        row = dict(path=dest, group=relative.parts[0], sha256=curate.sha256(root / dest),
                   size=(root / dest).stat().st_size)
        if arguments:
            row['verifier_args'] = arguments
        rows.append(row)
    return rows


def cost(rows, profile, policy, default_s, timeout, targeted=frozenset()):
    """Projected (lane-hours per arm, runs per arm, files without a profiled time)."""
    seconds, runs, missing = 0.0, 0, 0
    for row in rows:
        f = profile['files'].get(row['path'], {})
        k = night.seed_count(f, policy, row['path'] in targeted)
        wall = timeout if f.get('class') == 'always-timeout' else f.get('median_wall_s')
        if wall is None:
            missing += 1
            wall = default_s
        seconds += k * min(wall, timeout)
        runs += k
    return seconds / 3600, runs, missing


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('t0', help='build a T0 corpus root and manifest from a case list')
    p.add_argument('--spec', type=Path, required=True)
    p.add_argument('--corpus-root', type=Path, required=True, help='source of DEST-only lines')
    p.add_argument('--root', type=Path, required=True, help='new corpus root of the copies')
    p.add_argument('--output', type=Path, required=True)
    p = sub.add_parser('t1', help='interesting files plus a fixed sample of the rest')
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--profile', type=Path, required=True)
    p.add_argument('--slow', type=float, default=5.0, help='median wall seconds that make a file slow')
    p.add_argument('--sample', type=int, default=30)
    p.add_argument('--seed', type=int, default=20261026)
    p.add_argument('--output', type=Path, required=True)
    p = sub.add_parser('tags', help='write {path: [tag]} for a manifest')
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--corpus-root', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p = sub.add_parser('select', help='files with any given tag plus N others')
    p.add_argument('--manifest', type=Path, required=True)
    p.add_argument('--tags', type=Path, required=True)
    p.add_argument('--tag', action='append', required=True, choices=sorted(TAGS))
    p.add_argument('--sample', type=int, default=30)
    p.add_argument('--seed', type=int, default=20261026)
    p.add_argument('--output', type=Path, required=True)
    p = sub.add_parser('cost', help='projected lane-hours per arm')
    p.add_argument('--manifest', type=Path, required=True, action='append')
    p.add_argument('--profile', type=Path, required=True)
    p.add_argument('--seed-policy', default='')
    p.add_argument('--min-seeds', type=int, default=1)
    p.add_argument('--targeted', type=Path, action='append', default=[])
    p.add_argument('--default-seconds', type=float, default=30.0,
                   help='assumed wall of files the profile lacks')
    p.add_argument('--timeout', type=int, default=300)
    args = parser.parse_args(argv)
    try:
        if args.command == 't0':
            rows = build_t0(parse_spec(args.spec.read_text()), args.corpus_root.resolve(),
                            args.root.resolve())
        elif args.command == 'tags':
            rows = run.read_jsonl(args.manifest)
            result = tags(args.corpus_root, rows)
            args.output.write_text(json.dumps(result, indent=1, sort_keys=True) + '\n')
            counts = {t: sum(t in v for v in result.values()) for t in TAGS}
            return print(json.dumps(dict(files=len(result), tags=counts)))
        elif args.command == 'cost':
            policy = night.parse_policy(args.seed_policy, args.min_seeds)
            profile = json.loads(args.profile.read_text())
            targeted = {r['path'] for m in args.targeted for r in run.read_jsonl(m)}
            rows = {r['path']: r for m in args.manifest for r in run.read_jsonl(m)}.values()
            hours, runs, missing = cost(rows, profile, policy, args.default_seconds,
                                        args.timeout, targeted)
            return print(json.dumps(dict(files=len(rows), runs_per_arm=runs,
                                         lane_hours_per_arm=round(hours, 2), unprofiled=missing)))
        else:
            rows = run.read_jsonl(args.manifest)
            if args.command == 't1':
                rows = t1(rows, json.loads(args.profile.read_text()), args.slow, args.sample,
                          args.seed)
            else:
                rows = select(rows, json.loads(args.tags.read_text()), set(args.tag),
                              args.sample, args.seed)
    except (OSError, ValueError, KeyError) as exc:
        parser.exit(1, f'{exc}\n')
    curate.write_manifest(args.output, rows)
    print(json.dumps(dict(files=len(rows))))


if __name__ == '__main__':
    main()
