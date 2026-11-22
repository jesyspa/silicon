#!/usr/bin/env python3
"""Paired leave-one-out results: each arm against FULL measured alternately in one JVM.
Ratio of summed CPU (arm / FULL; > 1 means the change saves time) with a bootstrap interval over files,
the allocation ratio, the CPU saved, and the phase that changes most."""
import glob, json, random, collections, os
S = os.environ['BENCH_DIR']  # work directory
PH = ['Parsing', 'Semantic Analysis', 'Translation', 'Consistency Check', 'Verification', 'Backend utilities']
rows = []
for fn in sorted(glob.glob(f'{S}/runs/pair-*.jsonl')):
    by = collections.defaultdict(dict)
    for l in open(fn):
        d = json.loads(l); by[d['file']][d['arm']] = d
    arm = os.path.basename(fn)[5:-6]
    fs = [f for f, v in by.items() if arm in v and 'FULL' in v and 'Total' in v[arm]['phases'] and 'Total' in v['FULL']['phases']]
    dis = sum(1 for f in fs if by[f][arm]['status'] != by[f]['FULL']['status'])
    xs = [(by[f][arm]['phases']['Total'][0], by[f]['FULL']['phases']['Total'][0]) for f in fs]
    al = sum(by[f][arm]['phases']['Total'][2] for f in fs) / sum(by[f]['FULL']['phases']['Total'][2] for f in fs)
    r = sum(a for a, _ in xs) / sum(b for _, b in xs)
    rng = random.Random(0); bs = []
    for _ in range(1000):
        s = [xs[rng.randrange(len(xs))] for _ in xs]; bs.append(sum(a for a, _ in s) / sum(b for _, b in s))
    bs.sort()
    ph = {p: sum(by[f][arm]['phases'].get(p, [0])[0] - by[f]['FULL']['phases'].get(p, [0])[0] for f in fs) / 1e9 for p in PH}
    top = max(ph.items(), key=lambda kv: abs(kv[1]))
    full_s = sum(b for _, b in xs) / 1e9
    rows.append((r, arm, bs[25], bs[975], (sum(a for a, _ in xs) - sum(b for _, b in xs)) / 1e9, al, top, len(fs), dis, full_s))
print(f'{"arm (left out)":46s} {"files":>5s} {"cpu ratio":>9s} {"95% CI":>17s} {"saved s":>8s} {"alloc":>6s} {"FULL s":>7s}  main phase')
for r, arm, lo, hi, sv, al, (tp, tv), n, dis, fs_ in sorted(rows, reverse=True):
    print(f'{arm:46s} {n:5d} {r:9.4f} [{lo:.4f},{hi:.4f}] {sv:8.2f} {al:6.3f} {fs_:7.1f}  {tp} {tv:+.2f}s' + (f'  STATUS-DIFF {dis}' if dis else ''))
