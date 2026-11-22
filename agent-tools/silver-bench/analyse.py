#!/usr/bin/env python3
"""Summarises the Silver benchmark runs. For every arm: per-phase CPU time and allocation summed over the
files that completed in all arms (median over that arm's runs per file), and the leave-one-out effect against
FULL as the ratio of sums with a bootstrap interval over files, plus the per-file geometric mean."""
import glob, json, math, os, random, statistics, sys, collections
S = os.environ['BENCH_DIR']  # work directory
PH = ['Parsing', 'Semantic Analysis', 'Translation', 'Consistency Check', 'Verification', 'Backend utilities', 'Total']
data = collections.defaultdict(lambda: collections.defaultdict(list))  # arm -> file -> [phases dict]
status = collections.defaultdict(dict)
for fn in glob.glob(f'{S}/runs/r*-*.jsonl'):
    if not glob.glob(fn + '.done'): continue
    for l in open(fn):
        d = json.loads(l)
        data[d['arm']][d['file']].append(d['phases']); status[d['arm']][d['file']] = d['status']
arms = sorted(data)
files = sorted(set.intersection(*(set(data[a]) for a in arms)))
# status must agree across arms (a change must not alter frontend results)
dis = [f for f in files if len({status[a][f] for a in arms}) > 1]
print(f'arms {len(arms)}, common files {len(files)}, status disagreements {len(dis)} {dis[:5]}')
def med(arm, f, ph, k):  # k: 0 cpu ns, 2 alloc bytes
    xs = [p[ph][k] for p in data[arm][f] if ph in p]
    return statistics.median(xs) if xs else None
def table(arm, k):
    return {ph: sum(med(arm, f, ph, k) or 0 for f in files) for ph in PH}
runs = {a: len(next(iter(data[a].values()))) for a in arms}
full_cpu = table('FULL', 0); base_cpu = table('BASE', 0)
full_al = table('FULL', 2); base_al = table('BASE', 2)
print('\nPer phase, summed over common files (CPU s; allocation GB):')
print(f'{"phase":20s} {"BASE cpu":>9s} {"FULL cpu":>9s} {"ratio":>6s} {"BASE GB":>8s} {"FULL GB":>8s} {"ratio":>6s}')
for ph in PH:
    print(f'{ph:20s} {base_cpu[ph]/1e9:9.1f} {full_cpu[ph]/1e9:9.1f} {full_cpu[ph]/base_cpu[ph]:6.3f} '
          f'{base_al[ph]/1e9:8.1f} {full_al[ph]/1e9:8.1f} {full_al[ph]/base_al[ph]:6.3f}')
def boot(arm, ref, ph, k, n=1000):
    xs = [(med(arm, f, ph, k) or 0, med(ref, f, ph, k) or 0) for f in files]
    r = sum(a for a, _ in xs) / sum(b for _, b in xs)
    # runs[arm][i][f]: value of run i for file f; resample runs of both arms and the files
    def per_run(a):
        nr = min(len(data[a][f]) for f in files)
        return [[data[a][f][i][ph][k] if ph in data[a][f][i] else 0 for f in files] for i in range(nr)]
    ra, rr = per_run(arm), per_run(ref)
    rng = random.Random(0); bs = []
    for _ in range(n):
        ia = [rng.randrange(len(ra)) for _ in ra]; ir = [rng.randrange(len(rr)) for _ in rr]
        fi = [rng.randrange(len(files)) for _ in files]
        num = sum(sum(ra[i][j] for j in fi) for i in ia) / len(ia)
        den = sum(sum(rr[i][j] for j in fi) for i in ir) / len(ir)
        bs.append(num / den)
    bs.sort()
    g = [math.log(a / b) for a, b in xs if a > 0 and b > 0]
    return r, bs[25], bs[975], math.exp(sum(g) / len(g))
print('\nLeave-one-out: arm without the change against FULL (>1 means the change saves time).')
print(f'{"arm":46s} {"runs":>4s} {"cpu ratio":>9s} {"95% CI":>15s} {"geo":>6s} {"saved s":>8s} {"alloc":>6s}  biggest phase delta')
rows = []
for a in arms:
    if a == 'FULL': continue
    r, lo, hi, g = boot(a, 'FULL', 'Total', 0)
    al = table(a, 2)['Total'] / full_al['Total']
    t = table(a, 0)
    deltas = sorted(((t[ph] - full_cpu[ph]) / 1e9, ph) for ph in PH[:-1])
    rows.append((r, a, lo, hi, g, (t['Total'] - full_cpu['Total']) / 1e9, al, deltas[-1]))
for r, a, lo, hi, g, sv, al, (dv, dph) in sorted(rows, reverse=True):
    print(f'{a:46s} {runs[a]:4d} {r:9.4f} [{lo:.4f},{hi:.4f}] {g:6.3f} {sv:8.2f} {al:6.3f}  {dph} {dv:+.2f}s')
# FULL control stability: per-run totals
print('\nFULL control runs (CPU s total over common files):')
for fn in sorted(glob.glob(f'{S}/runs/r*-FULL.jsonl')):
    if not glob.glob(fn + '.done'): continue
    tot = sum(json.loads(l)['phases'].get('Total', [0])[0] for l in open(fn) if json.loads(l)['file'] in set(files))
    print(f'  {fn.split("/")[-1]:30s} {tot/1e9:.1f}')
