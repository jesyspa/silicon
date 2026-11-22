#!/usr/bin/env python3
"""Runs every arm once per round on two pinned CPU lanes, in a shuffled order per round, with extra FULL
control runs spread through each lane. Usage: BENCH_DIR=... sched.py ROUNDS FIRST_ROUND
Lanes are the two CPUs in $BENCH_CPUS (default "6 7"); results go to $BENCH_DIR/runs/."""
import os, random, subprocess, sys, threading, time, json
S = os.environ['BENCH_DIR']  # work directory
TOOL = os.path.dirname(os.path.abspath(__file__))
CPUS = [int(c) for c in os.environ.get('BENCH_CPUS', '6 7').split()]
arms = sorted(d for d in os.listdir(f'{S}/arms') if os.path.isdir(f'{S}/arms/{d}'))
rounds, first = int(sys.argv[1]), int(sys.argv[2])
log = open(f'{S}/runs/sched.log', 'a')
def say(*a):
    log.write(time.strftime('%H:%M:%S ') + ' '.join(map(str, a)) + '\n'); log.flush()
for r in range(first, first + rounds):
    order = arms[:]; random.Random(r).shuffle(order)
    lanes = {CPUS[0]: order[0::2], CPUS[1]: order[1::2]}
    for cpu in lanes:  # a FULL control at the start and middle of each lane
        lanes[cpu] = ['FULL'] + lanes[cpu][:7] + ['FULL'] + lanes[cpu][7:]
    def lane(cpu):
        for i, arm in enumerate(lanes[cpu]):
            out = f'{S}/runs/r{r}-cpu{cpu}-{i:02d}-{arm}.jsonl'
            if os.path.exists(out + '.done'): continue
            if os.path.exists(out): os.remove(out)
            load = os.getloadavg()[0]; t0 = time.time()
            rc = subprocess.call([f'{TOOL}/run-arm.sh', arm, str(cpu), f'{S}/subset.txt', out, '1', '1', f'{S}/warm300.txt'],
                                 stdout=subprocess.DEVNULL, stderr=open(out + '.err', 'w'))
            say(f'r{r} cpu{cpu} {i:02d} {arm} rc={rc} {time.time()-t0:.0f}s load={load:.1f}')
            if rc == 0: open(out + '.done', 'w').write(json.dumps({'load': load, 'secs': time.time() - t0}))
    ts = [threading.Thread(target=lane, args=(c,)) for c in lanes]
    [t.start() for t in ts]; [t.join() for t in ts]
    say(f'round {r} done')
