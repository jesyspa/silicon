#!/usr/bin/env python3
"""Profile per-file seed noise from night.py result directories.

Per file and JAR (by SHA-256) the profile records the wall-time spread across
seeds and a class: quiet (max/min < --quiet), mid (< --mid), noisy, flips (the
outcome changes with the seed) or always-timeout. A file's class is the worst
class of its JARs with at least two seeds, ranked quiet < mid < noisy < flips;
it is always-timeout only if every such JAR always times out. Capped runs are
censored at their cap and are left out. An unfinished last record is skipped,
so a running night can be read.
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import statistics

TIMED = ('verified', 'failed')
RANK = ('quiet', 'mid', 'noisy', 'flips')


def read_complete(path):
    """Complete JSONL records only; never repair a file another process may append to."""
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text().splitlines(keepends=True)
            if line.endswith('\n')]


def classify(samples, quiet, mid):
    """Class of one JAR's (outcome, wall) samples of one file, or None below two samples."""
    if len(samples) < 2:
        return None
    outcomes = {o for o, _ in samples}
    if outcomes == {'timeout'}:
        return 'always-timeout'
    if len(outcomes) > 1:
        return 'flips'
    walls = [w for _, w in samples]
    if outcomes - set(TIMED) or not all(w and w > 0 for w in walls):
        return None
    spread = max(walls) / min(walls)
    return 'quiet' if spread < quiet else 'mid' if spread < mid else 'noisy'


def file_class(classes):
    classes = [c for c in classes if c]
    if not classes:
        return 'unknown'
    finishing = [c for c in classes if c != 'always-timeout']
    return max(finishing, key=RANK.index) if finishing else 'always-timeout'


def profile(directories, quiet=1.25, mid=2.0):
    samples = defaultdict(lambda: defaultdict(list))
    arms = defaultdict(lambda: defaultdict(set))
    sources = []
    for directory in directories:
        config = json.loads((directory / 'run.json').read_text())
        jars = {name: arm['sha256'] for name, arm in config['arms'].items()}
        rows = read_complete(directory / 'results.jsonl')
        sources.append(dict(directory=str(directory), records=len(rows), arms=jars))
        for row in rows:
            jar = jars[row['arm']]
            arms[row['path']][jar].add(f'{directory.name}:{row["arm"]}')
            if row['outcome'] != 'capped':
                samples[row['path']][jar].append((row['outcome'], row.get('wall_s')))
    files = {}
    for path, by_jar in sorted(samples.items()):
        jars = {}
        for jar, s in sorted(by_jar.items()):
            walls = [w for o, w in s if o in TIMED and w]
            outcomes = defaultdict(int)
            for o, _ in s:
                outcomes[o] += 1
            jars[jar] = {'class': classify(s, quiet, mid), 'arms': sorted(arms[path][jar]),
                         'samples': len(s), 'outcomes': dict(outcomes)}
            jars[jar].update(min_s=min(walls, default=None), max_s=max(walls, default=None),
                             median_s=statistics.median(walls) if walls else None,
                             spread=max(walls) / min(walls) if len(walls) > 1 else None)
        medians = [j['median_s'] for j in jars.values() if j['median_s']]
        files[path] = {'class': file_class(j['class'] for j in jars.values()),
                       'median_wall_s': statistics.median(medians) if medians else None,
                       'jars': jars}
    return dict(schema=1, thresholds=dict(quiet=quiet, mid=mid), sources=sources, files=files)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument('directory', type=Path, nargs='+', help='night.py output directories')
    parser.add_argument('--output', type=Path, required=True, help='profile JSON to write')
    parser.add_argument('--quiet', type=float, default=1.25, help='max/min below which a file is quiet')
    parser.add_argument('--mid', type=float, default=2.0, help='max/min below which a file is mid')
    args = parser.parse_args(argv)
    try:
        report = profile([d.resolve() for d in args.directory], args.quiet, args.mid)
    except (OSError, ValueError, KeyError) as exc:
        parser.exit(1, f'{exc}\n')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix('.tmp')
    temporary.write_text(json.dumps(report, indent=1) + '\n')
    temporary.replace(args.output)
    counts = defaultdict(int)
    for f in report['files'].values():
        counts[f['class']] += 1
    print(json.dumps(dict(files=len(report['files']), classes=dict(sorted(counts.items())))))


if __name__ == '__main__':
    main()
