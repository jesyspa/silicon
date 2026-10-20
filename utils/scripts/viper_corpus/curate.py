#!/usr/bin/env python3
"""Curate deduplicated full and stratified quick Viper manifests."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import random


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def curate(root):
    seen = set()
    rows = []
    for path in sorted(root.rglob('*.vpr'), key=lambda p: p.relative_to(root).as_posix()):
        relative = path.relative_to(root)
        if '_preambles' in relative.parts or not path.is_file():
            continue
        digest = sha256(path)
        if digest in seen:
            continue
        seen.add(digest)
        rows.append(dict(path=relative.as_posix(), group=relative.parts[0],
                         sha256=digest, size=path.stat().st_size))
    return rows


def quick_subset(rows, target=250, cap=10, seed=0, largest=2):
    """Reserve capped coverage, then balance remaining slots across groups."""
    groups = defaultdict(list)
    for row in sorted(rows, key=lambda r: r['path']):
        groups[row['group']].append(row)
    rng = random.Random(seed)
    pools = {}
    selected = []
    for group, members in sorted(groups.items()):
        big = sorted(members, key=lambda r: (-r['size'], r['path']))[:largest]
        rest = [r for r in members if r not in big]
        rng.shuffle(rest)
        pools[group] = big + rest
    minimum = sum(min(cap, len(pool)) for pool in pools.values())
    if target < minimum:
        raise ValueError(f'target {target} is below capped group coverage {minimum}')
    for pool in pools.values():
        count = min(cap, len(pool))
        selected.extend(pool[:count])
        del pool[:count]
    while len(selected) < target and any(pools.values()):
        for pool in pools.values():
            if pool and len(selected) < target:
                selected.append(pool.pop(0))
    return sorted(selected, key=lambda r: r['path'])


def write_manifest(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(''.join(json.dumps(row, sort_keys=True) + '\n' for row in rows))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--corpus-root', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--quick', type=Path, required=True)
    parser.add_argument('--target', type=int, default=250)
    parser.add_argument('--group-cap', type=int, default=10)
    parser.add_argument('--seed', type=int, default=0)
    parser.add_argument('--largest-per-group', type=int, default=2)
    args = parser.parse_args(argv)
    if not args.corpus_root.is_dir():
        parser.error('corpus root is not a directory')
    if args.target <= 0 or args.group_cap <= 0 or args.largest_per_group < 0:
        parser.error('target and cap must be positive; largest count must be nonnegative')
    if args.manifest.resolve() == args.quick.resolve():
        parser.error('manifest and quick paths must differ')
    rows = curate(args.corpus_root)
    try:
        quick = quick_subset(rows, args.target, args.group_cap, args.seed,
                             args.largest_per_group)
    except ValueError as exc:
        parser.error(str(exc))
    write_manifest(args.manifest, rows)
    write_manifest(args.quick, quick)
    print(json.dumps(dict(full=len(rows), quick=len(quick))))


if __name__ == '__main__':
    main()
