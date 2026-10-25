#!/usr/bin/env python3
"""Analyse a seeded night.py run; ratios are arm / baseline.

Per file and arm the metric is the median across seeds. A file is unstable when
max/min across seeds exceeds --unstable in any arm. Timing compares a file only
when every seed of both arms has the same verified or failed outcome and a
positive metric. Stable files give a geometric mean ratio with a percentile
bootstrap CI over files; unstable files and outcome changes are listed apart.
GC pause share comes from the runners' -Xlog:gc logs.
"""
import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import random
import re
import statistics

from run import read_jsonl

METRICS = ('wall_s', 'total_process_cpu_s', 'solver_cpu_s', 'jvm_cpu_s')
TIMED = ('verified', 'failed')


def load(directories):
    """{path: {arm: {seed: record}}}, later directories overriding earlier ones."""
    files = defaultdict(lambda: defaultdict(dict))
    for directory in directories:
        for row in read_jsonl(directory / 'results.jsonl'):
            files[row['path']][row['arm']][row['seed']] = row
    return files


def value(record, metric):
    v = record.get(metric)
    if record['outcome'] in TIMED and isinstance(v, (int, float)) and math.isfinite(v) and v > 0:
        return v
    return None


def spread(records, metric):
    """max/min of the metric across seeds, or None if a seed lacks a value."""
    values = [value(r, metric) for r in records.values()]
    return None if None in values else max(values) / min(values)


def geomean(ratios):
    return math.exp(sum(math.log(r) for r in ratios) / len(ratios))


def bootstrap(ratios, samples, rng, level=0.95):
    if not ratios:
        return None
    means = sorted(geomean(rng.choices(ratios, k=len(ratios))) for _ in range(samples))
    tail = (1 - level) / 2
    return [means[int(tail * samples)], means[min(samples - 1, int((1 - tail) * samples))]]


def compare(files, baseline, arm, seeds, metric, threshold, samples, rng):
    ratios, unstable, excluded = [], [], 0
    for path, arms in sorted(files.items()):
        a, b = arms.get(baseline, {}), arms.get(arm, {})
        if set(a) != set(seeds) or set(b) != set(seeds):
            continue
        outcomes = {r['outcome'] for r in [*a.values(), *b.values()]}
        values = [value(r, metric) for r in [*a.values(), *b.values()]]
        if len(outcomes) != 1 or None in values:
            excluded += 1
            continue
        spreads = {name: s for name, recs in arms.items()
                   if set(recs) == set(seeds) and (s := spread(recs, metric)) is not None}
        if max(spreads.values()) > threshold:
            unstable.append(dict(path=path, spread={k: round(v, 2) for k, v in spreads.items()}))
            continue
        base = statistics.median(value(r, metric) for r in a.values())
        cand = statistics.median(value(r, metric) for r in b.values())
        ratios.append(dict(path=path, baseline=base, arm=cand, ratio=cand / base))
    values = [r['ratio'] for r in ratios]
    return dict(n=len(values), excluded=excluded, unstable=unstable,
                geomean=geomean(values) if values else None,
                ci95=bootstrap(values, samples, rng),
                regressions=sorted(ratios, key=lambda r: -r['ratio'])[:10],
                improvements=sorted(ratios, key=lambda r: r['ratio'])[:10])


def outcomes(files, arms, seeds):
    table = {arm: {seed: Counter() for seed in seeds} for arm in arms}
    across_seeds, across_arms = [], []
    for path, by_arm in sorted(files.items()):
        for arm, records in by_arm.items():
            for seed, r in records.items():
                table[arm][seed][r['outcome']] += 1
            if len({r['outcome'] for r in records.values()}) > 1:
                across_seeds.append(dict(path=path, arm=arm, outcomes={
                    s: records[s]['outcome'] for s in sorted(records)}))
        for seed in seeds:
            per_arm = {arm: by_arm[arm][seed]['outcome'] for arm in arms
                       if seed in by_arm.get(arm, {})}
            if len(set(per_arm.values())) > 1:
                across_arms.append(dict(path=path, seed=seed, outcomes=per_arm))
    return dict(table={a: {s: dict(c) for s, c in t.items()} for a, t in table.items()},
                across_seeds=across_seeds, across_arms=across_arms)


PAUSE = re.compile(r'GC\(\d+\) (Pause .*) (\d+(?:\.\d+)?)ms$')


def gc_share(directory, files, arms):
    """Per arm: logged GC pause seconds, excluding System.gc(), over request wall.

    The logs also cover warm-up requests, so their wall joins the denominator.
    """
    report = {}
    warmups = read_jsonl(directory / 'warmup' / 'wall.jsonl')
    for arm in arms:
        pause = 0.0
        for log in sorted((directory / 'gc').glob(f'{arm}-lane*.log*')):
            for line in log.read_text(errors='replace').splitlines():
                m = PAUSE.search(line)
                if m and 'System.gc()' not in m.group(1):
                    pause += float(m.group(2)) / 1000
        records = [r for by_arm in files.values() for r in by_arm.get(arm, {}).values()]
        wall = sum(r['wall_s'] for r in records if r.get('wall_s'))
        warmup = sum(w['wall_s'] for w in warmups if w['runner'].startswith(arm + '-lane'))
        mx = sum(r['gc_s'] for r in records if r.get('gc_s') is not None)
        report[arm] = dict(log_pause_s=round(pause, 1), wall_s=round(wall, 1),
                           warmup_wall_s=round(warmup, 1),
                           log_share=pause / (wall + warmup) if wall else None,
                           request_gc_s=round(mx, 1), request_share=mx / wall if wall else None)
    return report


def analyze(directories, baseline, metrics, threshold, samples, subsets, seed=0):
    files = load(directories)
    arms = sorted({a for by_arm in files.values() for a in by_arm})
    seeds = sorted({s for by_arm in files.values() for recs in by_arm.values() for s in recs})
    if baseline not in arms:
        raise ValueError('baseline arm has no results: ' + baseline)
    report = dict(arms=arms, seeds=seeds, baseline=baseline, unstable_threshold=threshold,
                  files=len(files), outcomes=outcomes(files, arms, seeds),
                  gc=gc_share(directories[-1], files, arms), subsets={})
    for name, paths in [('all', None), *subsets]:
        chosen = {p: v for p, v in files.items() if paths is None or p in paths}
        report['subsets'][name] = dict(files=len(chosen), comparisons={
            arm: {metric: compare(chosen, baseline, arm, seeds, metric, threshold, samples,
                                  random.Random(seed))
                  for metric in metrics}
            for arm in arms if arm != baseline})
    return report


def fmt(x, digits=3):
    return '-' if x is None else f'{x:.{digits}f}'


def markdown(report):
    out = [f'# Seeded run: arms {", ".join(report["arms"])}; seeds {report["seeds"]}; '
           f'{report["files"]} files; baseline {report["baseline"]}', '',
           f'Per-file median across seeds; unstable = max/min > {report["unstable_threshold"]} '
           'in any arm; 95% percentile bootstrap CI over stable files.', '']
    for name, subset in report['subsets'].items():
        out += [f'## Ratios: {name} ({subset["files"]} files)', '',
                '| arm | metric | n stable | geomean | 95% CI | unstable | excluded |',
                '|---|---|---|---|---|---|---|']
        for arm, metrics in subset['comparisons'].items():
            for metric, c in metrics.items():
                ci = c['ci95'] and f'[{fmt(c["ci95"][0])}, {fmt(c["ci95"][1])}]'
                out.append(f'| {arm} | {metric} | {c["n"]} | {fmt(c["geomean"])} | {ci or "-"} '
                           f'| {len(c["unstable"])} | {c["excluded"]} |')
        out.append('')
        # Instability spans all arms, so every comparison of a metric lists the same files.
        for metric, c in next(iter(subset['comparisons'].values()), {}).items():
            if c['unstable']:
                out.append(f'Unstable files ({metric}, max/min across seeds per arm): ' +
                           '; '.join(f'{u["path"]} {u["spread"]}' for u in c['unstable']))
        out.append('')
    o = report['outcomes']
    out += ['## Outcomes per arm and seed', '', '| arm | seed | outcomes |', '|---|---|---|']
    for arm, by_seed in o['table'].items():
        for seed, counts in by_seed.items():
            out.append(f'| {arm} | {seed} | ' +
                       ', '.join(f'{k} {v}' for k, v in sorted(counts.items())) + ' |')
    out += ['', f'### Outcome changes across seeds within an arm ({len(o["across_seeds"])})', '']
    out += [f'- {c["arm"]} {c["path"]}: {c["outcomes"]}' for c in o['across_seeds']]
    out += ['', f'### Outcome changes across arms at the same seed ({len(o["across_arms"])})', '']
    out += [f'- seed {c["seed"]} {c["path"]}: {c["outcomes"]}' for c in o['across_arms']]
    out += ['', '## GC pause share (non-System.gc pauses / request wall)', '',
            '| arm | -Xlog:gc pause s | request wall s | warm-up wall s | log share | MXBean share |',
            '|---|---|---|---|---|---|']
    for arm, g in report['gc'].items():
        out.append(f'| {arm} | {g["log_pause_s"]} | {g["wall_s"]} | {g["warmup_wall_s"]} '
                   f'| {fmt(g["log_share"])} '
                   f'| {fmt(g["request_share"])} |')
    return '\n'.join(out) + '\n'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument('directory', type=Path, nargs='+', help='night.py output directories')
    parser.add_argument('--baseline', required=True, help='baseline arm name')
    parser.add_argument('--metric', action='append', choices=METRICS,
                        help='repeatable; default wall_s and total_process_cpu_s')
    parser.add_argument('--unstable', type=float, default=2.0, help='max/min across seeds')
    parser.add_argument('--bootstrap', type=int, default=10000, help='resamples')
    parser.add_argument('--subset', action='append', default=[],
                        help='NAME=FILE: also report the corpus paths listed in FILE')
    parser.add_argument('--json', type=Path, help='also write the full report here')
    args = parser.parse_args(argv)
    subsets = []
    for spec in args.subset:
        name, _, path = spec.partition('=')
        lines = Path(path).read_text().splitlines()
        subsets.append((name, {l.strip() for l in lines if l.strip() and not l.startswith('#')}))
    try:
        report = analyze(args.directory, args.baseline,
                         args.metric or ['wall_s', 'total_process_cpu_s'],
                         args.unstable, args.bootstrap, subsets)
    except (OSError, ValueError) as exc:
        parser.exit(1, str(exc) + '\n')
    if args.json:
        args.json.write_text(json.dumps(report, indent=2) + '\n')
    print(markdown(report), end='')


if __name__ == '__main__':
    main()
