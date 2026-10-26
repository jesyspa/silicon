#!/usr/bin/env python3
"""Profile per-file seed noise from night.py result directories.

Per file and JAR (by SHA-256) the profile records the wall-time spread across
seeds and a class: quiet (max/min < --quiet), mid (< --mid), noisy, flips (the
outcome changes with the seed) or always-timeout.

A file is flips if any JAR flips, and always-timeout if every JAR with two or
more seeds always times out. Otherwise its class comes from the SD of log wall
time within each JAR, pooled over its finishing JARs, expressed as the expected
max/min of three seeds, exp(1.693 SD), and compared with the same thresholds.
Pooling keeps the class independent of how many JARs ran the file; the worst
JAR's class would drift toward noisy as JARs accumulate. Capped runs are
censored at their cap and are left out. An unfinished last record is skipped,
so a running night can be read.
"""
import argparse
from collections import defaultdict
import json
import math
from pathlib import Path
import statistics

TIMED = ('verified', 'failed')
D2 = 1.693  # expected range of three standard normal samples


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


def pooled_spread(by_jar):
    """exp(D2 * pooled within-JAR SD of log wall) over uniformly timed JARs, or None."""
    squares, freedom = 0.0, 0
    for samples in by_jar:
        logs = [math.log(w) for o, w in samples if o in TIMED and w and w > 0]
        if len(logs) < 2 or len(logs) != len(samples) or len({o for o, _ in samples}) > 1:
            continue
        mean = sum(logs) / len(logs)
        squares += sum((x - mean) ** 2 for x in logs)
        freedom += len(logs) - 1
    return math.exp(D2 * math.sqrt(squares / freedom)) if freedom else None


def file_class(classes, spread, quiet, mid):
    classes = [c for c in classes if c]
    if 'flips' in classes:
        return 'flips'
    if classes and all(c == 'always-timeout' for c in classes):
        return 'always-timeout'
    if spread is None:
        return 'unknown'
    return 'quiet' if spread < quiet else 'mid' if spread < mid else 'noisy'


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
        spread = pooled_spread(by_jar.values())
        files[path] = {'class': file_class([j['class'] for j in jars.values()], spread, quiet, mid),
                       'pooled_spread': spread,
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
