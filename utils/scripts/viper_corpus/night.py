#!/usr/bin/env python3
"""Run several Silicon arms over a manifest with Z3 seeds, interleaved per file.

Files are taken in a shuffled order. Each file is run by every arm with every
seed, in a shuffled order, before the next file starts, so a run stopped early
leaves every arm and seed with the same files, and load drift spreads evenly.
Every lane is a CPU set holding one warm runner per arm, each in its own
memory-limited scope. Results: <output>/results.jsonl, one record per
(file, arm, seed). Repeat the command to resume. Touch <output>/STOP to stop
after the running tasks.
"""
import argparse
import datetime
import fcntl
import json
import os
from pathlib import Path
import queue
import random
import shlex
import sys
import threading
import time
from types import SimpleNamespace

import run
from run import bench, sha256


def parse_arm(text):
    """'NAME=JAR [ARG...]': verifier arguments, or JVM arguments prefixed with -J."""
    first, *rest = shlex.split(text)
    name, separator, jar = first.partition('=')
    if not separator or not name or not jar or '/' in name:
        raise ValueError('arm must be NAME=JAR [ARG...]: ' + text)
    return dict(name=name, jar=Path(jar).resolve(),
                jvm_arg=[a[2:] for a in rest if a.startswith('-J')],
                verifier_arg=[a for a in rest if not a.startswith('-J')])


def parse_window(text):
    if text == 'any':
        return None
    start, end = text.split('-')
    return tuple(datetime.time.fromisoformat(t) for t in (start, end))


def in_window(window, now):
    if window is None:
        return True
    start, end = window
    t = now.time()
    return start <= t < end if start < end else t >= start or t < end


def plan(manifests, arms, seeds, order_seed):
    """Tasks (row, arm, seed): manifests in order, files shuffled within each, then arm x seed."""
    rng = random.Random(order_seed)
    seen, tasks = set(), []
    for rows in manifests:
        rows = [r for r in rows if r['path'] not in seen]
        seen.update(r['path'] for r in rows)
        rng.shuffle(rows)
        for row in rows:
            combos = [(arm, seed) for arm in arms for seed in seeds]
            rng.shuffle(combos)
            tasks += [(row, arm, seed) for arm, seed in combos]
    return tasks


class NightLane(run.Lane):
    """A warm runner that verifies the warm-up inputs after each start."""

    def __init__(self, *args, warmup=(), **kwargs):
        super().__init__(*args, **kwargs)
        self.warmup = warmup

    def start(self):
        super().start()
        directory = self.args.output_directory / 'warmup'
        directory.mkdir(exist_ok=True)
        for options in self.warmup:
            log = directory / f'{self.label}-{self.starts}.log'
            self.process.stdin.write(('\t'.join([str(log), *options]) + '\n').encode())
            self.process.stdin.flush()
            response = self.read(self.args.timeout + run.HANG_GRACE_S)
            if not response or response.get('fatal'):
                break  # the next request restarts the runner


def execute(args, arms, manifests):
    args.output_directory.mkdir(parents=True, exist_ok=True)
    with (args.output_directory / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        config = dict(schema=1, mode='night', corpus_root=str(args.corpus_root),
                      manifests=[sha256(m) for m in args.manifest], seeds=args.seeds,
                      order_seed=args.order_seed, timeout=args.timeout,
                      lane_memory_max=args.lane_memory_max, jvm_arg=args.jvm_arg,
                      verifier_arg=args.verifier_arg, warmup=args.warmup,
                      warmup_runs=args.warmup_runs,
                      artifacts={name: dict(path=str(getattr(args, name)),
                                            sha256=sha256(getattr(args, name)))
                                 for name in ('java', 'z3')},
                      arms={a['name']: dict(jar=str(a['jar']), sha256=sha256(a['jar']),
                                            jvm_arg=a['jvm_arg'], verifier_arg=a['verifier_arg'])
                            for a in arms})
        config_path = args.output_directory / 'run.json'
        if config_path.exists():
            if json.loads(config_path.read_text()) != config:
                raise ValueError('output directory belongs to a different run configuration')
        else:
            if (args.output_directory / 'results.jsonl').exists():
                raise ValueError('results exist without run.json')
            temporary = config_path.with_suffix('.tmp')
            temporary.write_text(json.dumps(config, indent=2) + '\n')
            temporary.replace(config_path)
        results = args.output_directory / 'results.jsonl'
        done = set()
        for row in run.read_jsonl(results, repair=True):
            key = (row['path'], row['arm'], row['seed'])
            if key in done:
                raise ValueError('duplicate result: ' + ' '.join(map(str, key)))
            done.add(key)
        by_name = {a['name']: a for a in arms}
        tasks = queue.Queue()
        for row, arm, seed in plan(manifests, list(by_name), args.seeds, args.order_seed):
            if (row['path'], arm, seed) not in done:
                tasks.put((row, arm, seed))
        if tasks.empty():
            return
        (args.output_directory / 'gc').mkdir(exist_ok=True)
        runner = run.build_runner(SimpleNamespace(**vars(args), jar=arms[0]['jar']))
        warmup = [['--prover', 'Z3', '--z3Exe', str(args.z3), '--timeout', str(args.timeout),
                   *args.verifier_arg, str(args.corpus_root / path)]
                  for path in args.warmup for _ in range(args.warmup_runs)]
        write = threading.Lock()

        def say(*words):
            with write:
                print(time.strftime('%H:%M:%S'), *words, file=log, flush=True)

        def lane(index, cpus):
            lanes = {}
            for arm in arms:
                gc = args.output_directory / 'gc' / f'{arm["name"]}-lane{index}-%p.log'
                lanes[arm['name']] = NightLane(
                    args, index, runner, jar=arm['jar'], cpus=cpus,
                    label=f'{arm["name"]}-lane{index}', warmup=[
                        [*w[:-1], *arm['verifier_arg'], w[-1]] for w in warmup],
                    jvm_arg=[*args.jvm_arg, *arm['jvm_arg'], f'-Xlog:gc:file={gc}:uptime'])
            try:
                while True:
                    if (args.output_directory / 'STOP').exists():
                        return say('lane', index, 'STOP')
                    if not in_window(args.window, datetime.datetime.now()):
                        return say('lane', index, 'outside window')
                    try:
                        row, name, seed = tasks.get_nowait()
                    except queue.Empty:
                        return
                    arm = by_name[name]
                    task = SimpleNamespace(**dict(vars(args), seed=seed, verifier_arg=[
                        *args.verifier_arg, *arm['verifier_arg']]))
                    record = dict(**row, **run.run_warm_once(row, task, lanes[name]),
                                  arm=name, cpus=cpus, returncode=None, errors=[])
                    with write:
                        sink.write(json.dumps(record, sort_keys=True) + '\n')
                        sink.flush()
                        os.fsync(sink.fileno())
                    say(record['outcome'], name, seed, f'{record["wall_s"] or 0:.1f}s', row['path'])
            finally:
                for l in lanes.values():
                    l.stop()

        with results.open('a') as sink, (args.output_directory / 'night.log').open('a') as log:
            say('start', tasks.qsize(), 'tasks')
            threads = [threading.Thread(target=lane, args=(i, cpus))
                       for i, cpus in enumerate(args.lanes)]
            for t in threads:
                t.start()
            for t in threads:
                t.join()
            say('end', tasks.qsize(), 'tasks left')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument('--corpus-root', required=True, type=Path)
    parser.add_argument('--manifest', required=True, type=Path, action='append',
                        help='repeatable; earlier manifests run first, repeated paths once')
    parser.add_argument('--output-directory', required=True, type=Path)
    parser.add_argument('--arm', required=True, action='append',
                        help="'NAME=JAR [ARG...]'; -J-prefixed ARGs go to the JVM")
    parser.add_argument('--seeds', default='0,1,2',
                        help='comma-separated Z3 seeds, merged into --proverArgs')
    parser.add_argument('--lanes', default='2,10;3,11;6,14', help='semicolon-separated CPU sets')
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--z3', type=Path, required=True)
    parser.add_argument('--timeout', type=int, default=300, help='per-input limit in seconds')
    parser.add_argument('--lane-memory-max', default='7G',
                        help='systemd MemoryMax of each runner scope, including its solvers')
    parser.add_argument('--jvm-arg', action='append', default=[])
    parser.add_argument('--verifier-arg', action='append', default=[])
    parser.add_argument('--warmup', action='append', default=[],
                        help='corpus-relative input verified, unrecorded, after each runner start')
    parser.add_argument('--warmup-runs', type=int, default=1)
    parser.add_argument('--order-seed', type=int, default=0, help='seed of the task shuffle')
    parser.add_argument('--window', default='19:00-08:00',
                        help="local HH:MM-HH:MM in which tasks may start, or 'any'")
    args = parser.parse_args(argv)
    try:
        arms = [parse_arm(a) for a in args.arm]
        args.seeds = [int(s) for s in args.seeds.split(',')]
        args.window = parse_window(args.window)
    except ValueError as exc:
        parser.error(str(exc))
    args.lanes = args.lanes.split(';')
    if len({a['name'] for a in arms}) != len(arms) or len(set(args.seeds)) != len(args.seeds):
        parser.error('arm names and seeds must be unique')
    if args.timeout <= 0 or args.warmup_runs <= 0:
        parser.error('timeout and warmup runs must be positive')
    for name in ('corpus_root', 'output_directory', 'java', 'z3'):
        setattr(args, name, getattr(args, name).resolve())
    args.manifest = [m.resolve() for m in args.manifest]
    for arm in arms:
        bench.arguments(['--java', str(args.java), '--jar', str(arm['jar']), '--z3', str(args.z3),
                         '--cgroup-root', '/sys/fs/cgroup',
                         '--output-directory', str(args.output_directory / 'validation'),
                         str(args.manifest[0]),
                         *['--jvm-arg=' + t for t in args.jvm_arg + arm['jvm_arg']],
                         *['--verifier-arg=' + t for t in args.verifier_arg + arm['verifier_arg']]])
    try:
        execute(args, arms, [run.load_manifest(m, args.corpus_root) for m in args.manifest])
    except (OSError, ValueError) as exc:
        parser.exit(1, str(exc) + '\n')


if __name__ == '__main__':
    main()
