#!/usr/bin/env python3
"""Run several Silicon arms over a manifest with Z3 seeds, interleaved per file.

Files are taken in a shuffled order. Each file is run by every arm with every
seed, in a shuffled order, before the next file starts, so a run stopped early
leaves every arm and seed with the same files, and load drift spreads evenly.
Every lane is a CPU set holding one warm runner per arm, each in its own
memory-limited scope. Results: <output>/results.jsonl, one record per
(file, arm, seed). Repeat the command to resume. Touch <output>/STOP to stop
after the running tasks.

With --profile (from noise.py), each file gets seeds 0..k-1, with k from its
noise class, the same for every arm. With --baseline, the baseline runs a file
first; if it finishes every seed, the other arms run that file with a timeout
of --cap-factor times its slowest baseline run, and a run cut by that timeout
is recorded as capped: a regression of at least the cap ratio. With
--extend-width, a file gets one more seed while the 95% t-interval of any
arm's per-seed paired log ratio is wider than that, up to --max-seeds.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import datetime
import fcntl
import json
import math
import os
from pathlib import Path
import random
import shlex
import shutil
import statistics
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


# Seeds per noise class; see method.md for the derivation. Files whose profiled
# median wall is below cheap_s seconds get at least `cheap` seeds.
POLICY = {'quiet': 2, 'mid': 3, 'noisy': 8, 'flips': 3, 'always-timeout': 1, 'unknown': 3,
          'targeted': 3, 'cheap': 3, 'cheap_s': 10}
TIMED = ('verified', 'failed')
T975 = [12.71, 4.30, 3.18, 2.78, 2.57, 2.45, 2.36, 2.31, 2.26, 2.23, 2.20, 2.18, 2.16, 2.14,
        2.13, 2.12, 2.11, 2.10, 2.09, 2.09]  # by degrees of freedom 1..20, then 2.0


def parse_policy(text, min_seeds=1):
    """'CLASS=N,...' over POLICY; `targeted` is used for always-timeout files a change targets."""
    policy = dict(POLICY, min=min_seeds)
    for item in filter(None, text.split(',')):
        key, _, number = item.partition('=')
        if key not in POLICY:
            raise ValueError('unknown seed policy key: ' + key)
        policy[key] = float(number) if key == 'cheap_s' else int(number)
    if min(policy.values()) <= 0:
        raise ValueError('seed counts must be positive')
    return policy


def seed_count(entry, policy, targeted=False):
    """Seeds for a file from its profile entry ({} when unprofiled).

    The seed floor skips always-timeout files the change does not target.
    """
    kind = entry.get('class', 'unknown')
    if kind == 'always-timeout':
        return max(policy['targeted'], policy['min']) if targeted else policy[kind]
    count = policy[kind]
    if (entry.get('median_wall_s') or math.inf) < policy['cheap_s']:
        count = max(count, policy['cheap'])
    return max(count, policy['min'])


def plan(manifests, arms, seeds, order_seed, baseline=None):
    """Tasks (row, arm, seed): manifests in order, files shuffled within each, then arm x seed.

    seeds is a list, or a function of the row. The baseline's tasks of a file come first.
    """
    rng = random.Random(order_seed)
    seen, tasks = set(), []
    for rows in manifests:
        rows = [r for r in rows if r['path'] not in seen]
        seen.update(r['path'] for r in rows)
        rng.shuffle(rows)
        for row in rows:
            tasks += block(row, arms, seeds(row) if callable(seeds) else seeds, rng, baseline)
    return tasks


def block(row, arms, seeds, rng, baseline=None):
    first = [(arm, seed) for arm in arms if arm == baseline for seed in seeds]
    combos = [(arm, seed) for arm in arms if arm != baseline for seed in seeds]
    rng.shuffle(first)
    rng.shuffle(combos)
    return [(row, arm, seed) for arm, seed in first + combos]


def cap(records, baseline, factor, low, high):
    """Timeout for a non-baseline arm from the baseline's records of the file, or high."""
    base = [r for (arm, _), r in records.items() if arm == baseline]
    if not base or any(r['outcome'] not in TIMED or not r.get('wall_s') for r in base):
        return high
    return min(high, max(low, math.ceil(factor * max(r['wall_s'] for r in base))))


def wide(records, arms, baseline, seeds, width):
    """Whether any arm's 95% t-interval of per-seed log(arm / baseline) is wider than width."""
    if len(seeds) < 2:
        return False
    for arm in arms:
        if arm == baseline:
            continue
        pairs = [(records.get((arm, s)), records.get((baseline, s))) for s in seeds]
        if any(not a or not b or a['outcome'] not in TIMED or b['outcome'] not in TIMED
               or not a.get('wall_s') or not b.get('wall_s') for a, b in pairs):
            return False
        logs = [math.log(a['wall_s'] / b['wall_s']) for a, b in pairs]
        t = T975[len(logs) - 2] if len(logs) - 2 < len(T975) else 2.0
        if t * statistics.stdev(logs) / math.sqrt(len(logs)) > width:
            return True
    return False


class Schedule:
    """Pending tasks in plan order, handed to lanes once ready.

    A non-baseline task waits until the baseline's planned tasks of its file are
    done, because their results set its timeout. Extra seeds join the front.
    """

    def __init__(self, tasks, records, arms, baseline=None, extend=None, rng=None):
        self.condition = threading.Condition()
        self.records = {}  # path -> {(arm, seed): record}
        for r in records:
            self.records.setdefault(r['path'], {})[r['arm'], r['seed']] = r
        self.arms, self.baseline, self.extend, self.rng = arms, baseline, extend, rng
        self.rows, self.seeds, self.open = {}, {}, {}
        self.pending, self.running = [], 0
        for row, arm, seed in tasks:
            self.rows[row['path']] = row
            self.seeds.setdefault(row['path'], set()).add(seed)
            if (arm, seed) not in self.records.get(row['path'], {}):
                self.add(row, arm, seed)
        for path in list(self.rows):
            self.grow(path)

    def add(self, row, arm, seed, at=None):
        self.pending.insert(len(self.pending) if at is None else at, (row, arm, seed))
        if arm == self.baseline:
            self.open[row['path']] = self.open.get(row['path'], 0) + 1

    def grow(self, path):
        """Plan more seeds of a finished file while its paired interval is still wide.

        A seed whose records exist, from before a resume, is taken as done.
        """
        records = self.records.get(path, {})
        while True:
            seeds = sorted(self.seeds[path])
            if not (self.extend and len(seeds) < self.extend[1] and
                    all((a, s) in records for a in self.arms for s in seeds) and
                    wide(records, self.arms, self.baseline, seeds, self.extend[0])):
                return
            self.seeds[path].add(seeds[-1] + 1)
            missing = [t for t in block(self.rows[path], self.arms, [seeds[-1] + 1], self.rng,
                                        self.baseline) if (t[1], t[2]) not in records]
            for index, task in enumerate(missing):
                self.add(*task, at=index)

    def get(self, timeout):
        """(task, finished): a ready task or None; finished once nothing remains."""
        with self.condition:
            for index, (row, arm, seed) in enumerate(self.pending):
                if arm == self.baseline or not self.open.get(row['path']):
                    self.running += 1
                    return self.pending.pop(index), False
            if not self.pending and not self.running:
                return None, True
            self.condition.wait(timeout)
            return None, False

    def timeout(self, row, arm, factor, low, high):
        if self.baseline is None or arm == self.baseline or not factor:
            return high
        with self.condition:
            return cap(self.records.get(row['path'], {}), self.baseline, factor, low, high)

    def complete(self, task, record):
        row, arm, seed = task
        with self.condition:
            self.running -= 1
            self.records.setdefault(row['path'], {})[arm, seed] = record
            if arm == self.baseline:
                self.open[row['path']] -= 1
            self.grow(row['path'])
            self.condition.notify_all()

    def requeue(self, task):
        with self.condition:
            self.running -= 1
            self.pending.insert(0, task)
            self.condition.notify_all()

    def __len__(self):
        with self.condition:
            return len(self.pending)


class WarmupFailure(OSError):
    """A lane could not establish a warmed runner after retrying."""


class NightLane(run.Lane):
    """A warm runner that verifies the warm-up inputs after each start."""

    def __init__(self, *args, warmup=(), **kwargs):
        super().__init__(*args, **kwargs)
        self.warmup = warmup

    def start(self):
        for attempt in range(2):
            try:
                super().start()
                self.warm_up()
                return
            except (OSError, run.RunnerProtocolError) as exc:
                self.stop()
                with (self.args.output_directory / f'runner-{self.label}.log').open('a') as log:
                    log.write(f'Warm-up attempt {attempt + 1} failed: {exc}\n')
                if attempt == 1:
                    raise WarmupFailure(f'{self.label}: warm-up failed twice: {exc}') from exc

    def warm_up(self):
        directory = self.args.output_directory / 'warmup'
        directory.mkdir(exist_ok=True)
        for options in self.warmup:
            log = directory / f'{self.label}-{self.starts}.log'
            response, failure = self.request(log, options)
            if response:  # analyze.py counts warm-up wall against the runner's GC log
                with (directory / 'wall.jsonl').open('a') as sink:
                    sink.write(json.dumps(dict(runner=self.label, wall_s=response['wall_s'])) + '\n')
            if failure or not response or response['fatal'] or not response['success']:
                reason = failure or ('fatal runner error' if response and response['fatal']
                                     else 'unsuccessful verification')
                raise OSError(f'warm-up failed for {self.label}: {reason}; see {log}')


def execute(args, arms, manifests):
    args.output_directory.mkdir(parents=True, exist_ok=True)
    with (args.output_directory / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        # Resumes use this snapshot, so later noise.py runs cannot change the plan.
        profile = args.output_directory / 'profile.json'
        extra = {}
        if getattr(args, 'profile', None):
            extra = dict(profile=sha256(profile if profile.exists() else args.profile),
                         seed_policy=args.seed_policy,
                         targeted=[sha256(m) for m in args.targeted])
        if getattr(args, 'baseline', None):
            extra.update(baseline=args.baseline, cap_factor=args.cap_factor, cap_min=args.cap_min,
                         extend=[args.extend_width, args.max_seeds] if args.extend_width else None)
        config = dict(schema=1, mode='night', corpus_root=str(args.corpus_root), **extra,
                      manifests=[sha256(m) for m in args.manifest], seeds=args.seeds,
                      order_seed=args.order_seed, timeout=args.timeout,
                      lane_memory_max=args.lane_memory_max, slice=args.slice, jvm_arg=args.jvm_arg,
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
        if getattr(args, 'profile', None) and not profile.exists():
            shutil.copyfile(args.profile, profile)
        results = args.output_directory / 'results.jsonl'
        done = set()
        for row in run.read_jsonl(results, repair=True):
            key = (row['path'], row['arm'], row['seed'])
            if key in done:
                raise ValueError('duplicate result: ' + ' '.join(map(str, key)))
            done.add(key)
        by_name = {a['name']: a for a in arms}
        seeds = args.seeds
        if getattr(args, 'profile', None):
            files = json.loads(profile.read_text())['files']
            targeted = {r['path'] for m in args.targeted for r in run.read_jsonl(m)}
            seeds = lambda row: list(range(seed_count(files.get(row['path'], {}), args.seed_policy,
                                                      row['path'] in targeted)))
        baseline = getattr(args, 'baseline', None)
        extend = baseline and args.extend_width and (args.extend_width, args.max_seeds)
        tasks = Schedule(plan(manifests, list(by_name), seeds, args.order_seed, baseline),
                         run.read_jsonl(results), list(by_name), baseline, extend,
                         random.Random(args.order_seed + 1))
        if not len(tasks):
            return
        (args.output_directory / 'gc').mkdir(exist_ok=True)
        runner = run.build_runner(SimpleNamespace(**vars(args), jar=arms[0]['jar']))
        warmup = [['--prover', 'Z3', '--z3Exe', str(args.z3), '--timeout', str(args.timeout),
                   *args.verifier_arg, str(args.corpus_root / path)]
                  for path in args.warmup for _ in range(args.warmup_runs)]
        write = threading.Lock()
        failed = threading.Event()
        dead_lanes = []

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
                    jvm_arg=[*args.jvm_arg, *arm['jvm_arg'], f'-Xlog:gc,gc+cpu:file={gc}:uptime'])
            try:
                while not failed.is_set():
                    if (args.output_directory / 'STOP').exists():
                        return say('lane', index, 'STOP')
                    if not in_window(args.window, datetime.datetime.now()):
                        return say('lane', index, 'outside window')
                    # Idle lanes stay available while another lane can still
                    # return a task after exhausting its warm-up retry.
                    item, finished = tasks.get(timeout=0.25)
                    if finished:
                        return
                    if item is None:
                        continue
                    row, name, seed = item
                    arm = by_name[name]
                    limit = tasks.timeout(row, name, getattr(args, 'cap_factor', None),
                                          getattr(args, 'cap_min', None), args.timeout)
                    task = SimpleNamespace(**dict(vars(args), seed=seed, timeout=limit, verifier_arg=[
                        *args.verifier_arg, *arm['verifier_arg']]))
                    try:
                        record = dict(**row, **run.run_warm_once(row, task, lanes[name]),
                                      arm=name, cpus=cpus, returncode=None, errors=[],
                                      timeout_s=limit)
                    except WarmupFailure as exc:
                        tasks.requeue(item)
                        with write:
                            dead_lanes.append(index)
                        say('lane', index, 'dead', str(exc), 'requeued', name, seed, row['path'])
                        return
                    except BaseException:
                        tasks.requeue(item)
                        raise
                    if record['outcome'] == 'timeout' and limit < args.timeout:
                        record['outcome'] = 'capped'
                    with write:
                        sink.write(json.dumps(record, sort_keys=True) + '\n')
                        sink.flush()
                        os.fsync(sink.fileno())
                    tasks.complete(item, record)
                    say(record['outcome'], name, seed, f'{record["wall_s"] or 0:.1f}s', row['path'])
            except BaseException:
                failed.set()
                raise
            finally:
                for l in lanes.values():
                    l.stop()

        with results.open('a') as sink, (args.output_directory / 'night.log').open('a') as log:
            say('start', len(tasks), 'tasks')
            # Propagate worker failures to the CLI; joining raw threads otherwise
            # reports a successful run after a warm-up or protocol failure.
            with ThreadPoolExecutor(max_workers=len(args.lanes)) as pool:
                futures = [pool.submit(lane, i, cpus) for i, cpus in enumerate(args.lanes)]
                for future in futures:
                    future.result()
            say('end', len(tasks), 'tasks left', len(dead_lanes), 'dead lanes')
            if len(dead_lanes) == len(args.lanes) and len(tasks):
                raise OSError(f'all lanes failed warm-up; {len(tasks)} tasks remain resumable')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument('--corpus-root', required=True, type=Path)
    parser.add_argument('--manifest', required=True, type=Path, action='append',
                        help='repeatable; earlier manifests run first, repeated paths once')
    parser.add_argument('--output-directory', required=True, type=Path)
    parser.add_argument('--arm', required=True, action='append',
                        help="'NAME=JAR [ARG...]'; -J-prefixed ARGs go to the JVM")
    parser.add_argument('--seeds', help='comma-separated Z3 seeds, merged into --proverArgs; '
                                         'default 0,1,2 without --profile')
    parser.add_argument('--profile', type=Path,
                        help='noise.py profile; each file gets seeds 0..k-1 by its class')
    parser.add_argument('--seed-policy', default='',
                        help='CLASS=N,... over ' + ','.join(f'{k}={v}' for k, v in POLICY.items()))
    parser.add_argument('--min-seeds', type=int, default=1, help='seed floor with --profile')
    parser.add_argument('--targeted', type=Path, action='append', default=[],
                        help='manifest of files the change targets: always-timeout files among '
                             'them get the targeted seed count instead of 1')
    parser.add_argument('--baseline', help='arm run first; caps the timeouts of the others')
    parser.add_argument('--cap-factor', type=float, default=4.0,
                        help='with --baseline: other arms get this times the slowest baseline run')
    parser.add_argument('--cap-min', type=int, default=30, help='lowest capped timeout in seconds')
    parser.add_argument('--extend-width', type=float,
                        help='with --baseline and --profile: add seeds while the paired log-ratio '
                             '95%% interval half-width exceeds this')
    parser.add_argument('--max-seeds', type=int, default=10, help='seed limit of --extend-width')
    parser.add_argument('--lanes', default='2,10;3,11;6,14;7,15;5,13',
                        help='semicolon-separated CPU sets')
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--z3', type=Path, required=True)
    parser.add_argument('--timeout', type=int, default=300, help='per-input limit in seconds')
    parser.add_argument('--lane-memory-max', default='7G',
                        help='systemd MemoryMax of each runner scope, including its solvers')
    parser.add_argument('--slice', default='silicon.slice',
                        help='systemd user slice holding all runner scopes; its MemoryMax is the '
                             'budget of all Silicon work on the host')
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
        if args.profile and args.seeds:
            raise ValueError('--profile chooses the seeds; omit --seeds')
        args.seeds = None if args.profile else [int(s) for s in (args.seeds or '0,1,2').split(',')]
        args.seed_policy = parse_policy(args.seed_policy, args.min_seeds)
        args.window = parse_window(args.window)
    except ValueError as exc:
        parser.error(str(exc))
    args.lanes = args.lanes.split(';')
    if len({a['name'] for a in arms}) != len(arms) or len(set(args.seeds or ())) != len(args.seeds or ()):
        parser.error('arm names and seeds must be unique')
    if args.timeout <= 0 or args.warmup_runs <= 0:
        parser.error('timeout and warmup runs must be positive')
    if args.baseline is not None and args.baseline not in {a['name'] for a in arms}:
        parser.error('--baseline must name an arm')
    if args.extend_width and not (args.baseline and args.profile):
        parser.error('--extend-width needs --baseline and --profile')
    if args.cap_factor <= 0 or args.cap_min <= 0 or args.max_seeds <= 0:
        parser.error('cap factor, cap minimum and seed limit must be positive')
    # Warm-runner defaults go to each arm unless its arguments, global or own, set them.
    for arm in arms:
        jvm, verifier = run.warm_defaults([*args.jvm_arg, *arm['jvm_arg']],
                                          [*args.verifier_arg, *arm['verifier_arg']])
        arm['jvm_arg'] += jvm[len(args.jvm_arg) + len(arm['jvm_arg']):]
        arm['verifier_arg'] += verifier[len(args.verifier_arg) + len(arm['verifier_arg']):]
    for name in ('corpus_root', 'output_directory', 'java', 'z3'):
        setattr(args, name, getattr(args, name).resolve())
    args.manifest = [m.resolve() for m in args.manifest]
    args.targeted = [m.resolve() for m in args.targeted]
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
