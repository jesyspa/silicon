#!/usr/bin/env python3
"""Run a Viper manifest through the solver CPU benchmark in isolated processes."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import fcntl
import json
import os
import queue
import select
import signal
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import solver_cpu_benchmark as bench
from curate import sha256


def read_jsonl(path, repair=False):
    if not path.exists():
        return []
    rows = []
    with path.open('rb+') if repair else path.open('rb') as source:
        while True:
            start = source.tell()
            line = source.readline()
            if not line:
                break
            if not line.endswith(b'\n'):
                if repair:
                    source.truncate(start)
                    break
                raise ValueError(f'{path}: incomplete last record')
            rows.append(json.loads(line))
    return rows


def load_manifest(path, root):
    rows = read_jsonl(path)
    seen = set()
    for row in rows:
        relative = PurePosixPath(row['path'])
        if (relative.is_absolute() or '..' in relative.parts or
                not relative.parts or relative.parts[0] != row['group'] or
                relative.suffix != '.vpr' or row['path'] in seen):
            raise ValueError('invalid or repeated manifest path: ' + row['path'])
        seen.add(row['path'])
        source = (root / row['path']).resolve()
        if not source.is_relative_to(root.resolve()):
            raise ValueError('input escapes corpus root: ' + row['path'])
        if source.stat().st_size != row['size'] or sha256(source) != row['sha256']:
            raise ValueError('input differs from manifest: ' + row['path'])
    return rows


def outcome(measurement, log):
    if any(measurement.get(key, {}).get('oom_kill', 0)
           for key in ('memory_events', 'process_memory_events')):
        return 'oom'
    if measurement.get('timed_out'):
        return 'timeout'
    if measurement.get('errors') or measurement.get('leftover_failure'):
        return 'crash'
    code = measurement.get('returncode')
    if re.search(r'(?i)verification[^\n]*(?:timed out|timeout)|timeout occurred after', log):
        return 'timeout'
    if re.search(r'(?i)exception in thread|an exception occurred|internal error|'
                 r'java\.lang\.[\w.]+(?:Exception|Error)|verification aborted exceptionally', log):
        return 'crash'
    if code == 0 and measurement.get('success'):
        return 'verified'
    # Silicon also exits 1 for caught internal exceptions. Require a normal
    # verification summary to distinguish those from expected negative tests.
    if code == 1 and re.search(r'(?i)verification failed|\b[1-9][0-9]* errors? found\b|'
                               r'the following errors? (?:was|were) found|'
                               r'\bSilicon found [1-9][0-9]* errors?\b', log):
        return 'failed'
    return 'crash'


SEED_PARAMETERS = ('smt.random_seed', 'sat.random_seed')


def verifier_args(row, args):
    """Common then per-input Silicon arguments, with the seed merged into --proverArgs.

    Silicon takes one --proverArgs string, so the seed joins an existing one.
    """
    tokens = [*args.verifier_arg, *row.get('verifier_args', [])]
    seed = getattr(args, 'seed', None)
    if seed is None:
        return tokens
    extra = ' '.join(f'{name}={seed}' for name in SEED_PARAMETERS)
    for index, token in enumerate(tokens):
        if token == '--proverArgs' and index + 1 < len(tokens):
            return [*tokens[:index + 1], tokens[index + 1] + ' ' + extra, *tokens[index + 2:]]
        if token.startswith('--proverArgs='):
            return [*tokens[:index], token + ' ' + extra, *tokens[index + 1:]]
    return [*tokens, '--proverArgs', extra]


def run_one(row, args):
    relative = Path('inputs') / (row['sha256'] + '-' + uuid.uuid4().hex)
    directory = args.output_directory / relative
    command = [sys.executable, str(Path(bench.__file__).resolve()),
               '--cgroup-root', str(args.cgroup_root), '--java', str(args.java),
               '--jar', str(args.jar), '--z3', str(args.z3),
               '--output-directory', str(directory), '--timeout', str(args.timeout)]
    for option in ('solver_memory_max', 'solver_swap_max'):
        value = getattr(args, option, None)
        if value is not None:
            command += ['--' + option.replace('_', '-'), str(value)]
    command += ['--jvm-arg=' + token for token in args.jvm_arg]
    command += ['--verifier-arg=' + token for token in verifier_args(row, args)]
    command += [str(args.corpus_root / row['path'])]
    directory.parent.mkdir(parents=True, exist_ok=True)
    # The reused benchmark owns timeout, process-group termination, cgroups,
    # signal handling and measurement persistence; threads only schedule it.
    completed = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    measurement_path = directory / 'measurement.json'
    measurement = json.loads(measurement_path.read_text()) if measurement_path.exists() else {}
    log_path = directory / 'stdout.log'
    log = log_path.read_text(errors='replace') if log_path.exists() else ''
    return dict(**row, seed=getattr(args, 'seed', None), outcome=outcome(measurement, log),
                solver_cpu_s=measurement.get('solver_cpu_s', {}).get('usage'),
                total_process_cpu_s=measurement.get('total_process_cpu_s'),
                wall_s=measurement.get('elapsed_s'),
                returncode=measurement.get('returncode'),
                measurement=str(relative / 'measurement.json'),
                errors=measurement.get('errors', []) + (
                    [completed.stderr.decode(errors='replace')] if not measurement else []))


RUNNER = Path(__file__).with_name('WarmRunner.java')
HANG_GRACE_S = 30
OOM_EXIT = 3  # -XX:+ExitOnOutOfMemoryError


def build_runner(args):
    """Compile WarmRunner against the measured JAR into the output directory."""
    target = args.output_directory / 'runner.jar'
    with tempfile.TemporaryDirectory() as classes:
        subprocess.run([str(args.java.with_name('javac')), '--release', '11', '-cp', str(args.jar),
                        '-d', classes, str(RUNNER)], check=True)
        subprocess.run([str(args.java.with_name('jar')), 'cf', str(target), '-C', classes, '.'],
                       check=True)
    return target


def process_cgroup(pid):
    return Path('/sys/fs/cgroup' + Path(f'/proc/{pid}/cgroup').read_text().split(':', 2)[2].strip())


def oom_kills(cgroup):
    for line in (cgroup / 'memory.events').read_text().splitlines():
        key, value = line.split()
        if key == 'oom_kill':
            return int(value)
    raise OSError('memory.events lacks oom_kill: ' + str(cgroup))


class Lane:
    """One long-lived WarmRunner in its own memory-limited scope.

    The runner is restarted after it dies, hangs, hits an Error, or the kernel
    OOM-kills a process (in practice Z3) in its scope.
    """

    def __init__(self, args, index, runner, jar=None, cpus=None, label=None, jvm_arg=None):
        self.args, self.index, self.runner = args, index, runner
        self.jar, self.cpus = jar or args.jar, cpus
        self.label = label or str(index)
        self.jvm_arg = args.jvm_arg if jvm_arg is None else jvm_arg
        self.process, self.starts, self.served = None, 0, 0

    def scope(self):
        # OOMPolicy=continue keeps the runner alive when the kernel kills its Z3.
        return ['systemd-run', '--user', '--scope', '--quiet', '-p', 'OOMPolicy=continue',
                '-p', 'MemoryMax=' + self.args.lane_memory_max, '-p', 'MemorySwapMax=0', '--']

    def start(self):
        stderr = (self.args.output_directory / f'runner-{self.label}.log').open('ab')
        pin = ['taskset', '-c', self.cpus] if self.cpus else []
        self.process = subprocess.Popen(
            [*self.scope(), *pin, str(self.args.java), *self.jvm_arg,
             '-XX:+ExitOnOutOfMemoryError', '-cp', f'{self.runner}:{self.jar}', 'WarmRunner'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=stderr, start_new_session=True)
        stderr.close()
        self.starts, self.served = self.starts + 1, 0
        if self.read(60) != {'ready': True}:
            raise OSError('warm runner did not start; see ' + stderr.name)
        # systemd-run executes the runner in place, so the scope is the runner's cgroup.
        self.cgroup = process_cgroup(self.process.pid)

    def read(self, timeout):
        """Return the next response, {} if the runner exited, or None after the timeout."""
        ready, _, _ = select.select([self.process.stdout], [], [], timeout)
        if not ready:
            return None
        line = self.process.stdout.readline()
        return json.loads(line) if line.endswith(b'\n') else {}

    def stop(self):
        """Kill the runner and its solvers; return the runner's exit status."""
        if self.process is None:
            return None
        with self.process as process:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        self.process = None
        return process.returncode

    def verify(self, log, options):
        """Return (response or None, failed outcome, runner context) for one request."""
        if self.process is None or self.process.poll() is not None:
            self.stop()
            self.start()
        context = dict(lane=self.index, runner_start=self.starts, runner_served=self.served,
                       after_restart=self.starts > 1)
        self.served += 1
        kills = oom_kills(self.cgroup)
        try:
            self.process.stdin.write(('\t'.join([str(log), *options]) + '\n').encode())
            self.process.stdin.flush()
        except BrokenPipeError:
            pass
        response = self.read(self.args.timeout + HANG_GRACE_S)
        try:
            killed = oom_kills(self.cgroup) > kills
        except FileNotFoundError:  # systemd removed the scope of a dead runner
            killed = False
        if response:
            if response['fatal'] or killed:
                self.stop()
            return response, 'oom' if killed else None, context
        hung = response is None and not killed
        code = self.stop()
        # A SIGKILL we did not send is the OOM killer choosing the runner over its
        # solver; the scope, and with it memory.events, is then already gone.
        oom = killed or code in (OOM_EXIT, -signal.SIGKILL)
        failure = 'timeout' if hung else 'oom' if oom else 'crash'
        return None, failure, context


def run_warm_once(row, args, lane):
    relative = Path('inputs') / (row['sha256'] + '-' + uuid.uuid4().hex)
    directory = args.output_directory / relative
    directory.mkdir(parents=True)
    options = ['--prover', 'Z3', '--z3Exe', str(args.z3), '--timeout', str(args.timeout),
               *verifier_args(row, args), str(args.corpus_root / row['path'])]
    response, failure, context = lane.verify(directory / 'stdout.log', options)
    measurement = dict(response or {}, **context, failure=failure)
    (directory / 'measurement.json').write_text(json.dumps(measurement, indent=2) + '\n')
    log_path = directory / 'stdout.log'
    log = log_path.read_text(errors='replace') if log_path.exists() else ''
    tick = os.sysconf('SC_CLK_TCK')
    result = dict(outcome=failure or outcome(dict(
                      returncode=0 if response['success'] else 1, success=response['success']), log),
                  wall_s=None, solver_cpu_s=None, total_process_cpu_s=None, jvm_cpu_s=None,
                  heap_after_gc_bytes=None, gc=None, gc_s=None, seed=getattr(args, 'seed', None),
                  measurement=str(relative / 'measurement.json'), **context)
    if response:
        result.update(wall_s=response['wall_s'], solver_cpu_s=response['children_cpu_ticks'] / tick,
                      jvm_cpu_s=response['jvm_cpu_ticks'] / tick,
                      total_process_cpu_s=(response['jvm_cpu_ticks'] +
                                           response['children_cpu_ticks']) / tick,
                      heap_after_gc_bytes=response['heap_after_gc_bytes'])
        if 'gc' in response:  # runners built before GC accounting omit it
            result.update(gc=response['gc'], gc_s=sum(
                c['ms'] for name, c in response['gc'].items() if 'Concurrent' not in name) / 1000)
    return result


def run_warm(row, args, lanes):
    lane = lanes.get()
    try:
        runs = [run_warm_once(row, args, lane) for _ in range(args.warm_runs)]
    finally:
        lanes.put(lane)
    # The last run is the measurement; earlier warm-up runs are kept alongside it.
    return dict(**row, **runs[-1], returncode=None, errors=[], warmup=runs[:-1])


def execute(args, rows):
    args.output_directory.mkdir(parents=True, exist_ok=True)
    with (args.output_directory / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        config = dict(schema=1, corpus_root=str(args.corpus_root),
                      manifest=sha256(args.manifest), timeout=args.timeout,
                      jvm_arg=args.jvm_arg, verifier_arg=args.verifier_arg,
                      artifacts={name: dict(path=str(getattr(args, name)),
                                            sha256=sha256(getattr(args, name)))
                                 for name in ('java', 'jar', 'z3')})
        for option in ('solver_memory_max', 'solver_swap_max', 'seed'):
            value = getattr(args, option, None)
            if value is not None:
                config[option] = value
        if args.warm:
            config.update(mode='warm', warm_runs=args.warm_runs,
                          lane_memory_max=args.lane_memory_max)
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
        existing = read_jsonl(results, repair=True)
        by_path = {r['path']: r for r in rows}
        done = set()
        for row in existing:
            if (row['path'] not in by_path or row['sha256'] != by_path[row['path']]['sha256']
                    or row['path'] in done):
                raise ValueError('results do not match manifest or contain duplicate paths')
            done.add(row['path'])
        pending = [row for row in rows if row['path'] not in done]
        lanes = queue.Queue()
        if args.warm and pending:
            runner = build_runner(args)
            for index in range(args.jobs):
                lanes.put(Lane(args, index, runner))
        with results.open('a') as sink, ThreadPoolExecutor(max_workers=args.jobs) as pool:
            futures = [pool.submit(run_warm, row, args, lanes) if args.warm else
                       pool.submit(run_one, row, args) for row in pending]
            try:
                for future in as_completed(futures):
                    record = future.result()
                    sink.write(json.dumps(record, sort_keys=True) + '\n')
                    sink.flush()
                    os.fsync(sink.fileno())
                    print(record['outcome'] + ' ' + record['path'], flush=True)
            except BaseException:
                # Do not start the rest of the corpus after an interruption.
                pool.shutdown(wait=True, cancel_futures=True)
                raise
            finally:
                while not lanes.empty():
                    lanes.get().stop()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument('--corpus-root', required=True, type=Path)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--output-directory', required=True, type=Path)
    parser.add_argument('--cgroup-root', type=Path, help='required unless --warm')
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--java', type=Path, default=Path(shutil.which('java') or 'java'))
    parser.add_argument('--z3', type=Path, default=Path(shutil.which('z3') or 'z3'))
    parser.add_argument('--jobs', type=int, default=6, help='parallel input jobs')
    parser.add_argument('--timeout', type=int, default=300, help='per-input wall limit in seconds')
    parser.add_argument('--solver-memory-max', type=int, help='solver leaf memory.max in bytes')
    parser.add_argument('--solver-swap-max', type=int, help='solver leaf memory.swap.max in bytes')
    parser.add_argument('--jvm-arg', action='append', default=[])
    parser.add_argument('--verifier-arg', action='append', default=[])
    parser.add_argument('--seed', type=int,
                        help='Z3 ' + ' and '.join(SEED_PARAMETERS) + ', merged into --proverArgs')
    parser.add_argument('--warm', action='store_true',
                        help='verify in one long-lived JVM per job instead of cgroup-isolated processes')
    parser.add_argument('--warm-runs', type=int, default=1,
                        help='verifications per file in --warm mode; the last is measured')
    parser.add_argument('--lane-memory-max', default='7G',
                        help='systemd MemoryMax of each --warm runner scope, including its solvers')
    args = parser.parse_args(argv)
    if args.warm != (args.cgroup_root is None):
        parser.error('use exactly one of --warm and --cgroup-root')
    if args.warm and (args.solver_memory_max is not None or args.solver_swap_max is not None):
        parser.error('solver memory limits need cgroups and are not supported with --warm')
    if args.warm_runs <= 0 or (args.warm_runs != 1 and not args.warm):
        parser.error('--warm-runs must be positive and needs --warm')
    if args.jobs <= 0 or args.timeout <= 0:
        parser.error('jobs and timeout must be positive')
    if any(v is not None and v < 0 for v in (args.solver_memory_max, args.solver_swap_max)):
        parser.error('solver memory/swap limits must be nonnegative')
    for name in ('corpus_root', 'manifest', 'output_directory', 'cgroup_root', 'jar', 'java', 'z3'):
        if getattr(args, name) is not None:
            setattr(args, name, getattr(args, name).resolve())
    # Reuse the benchmark's option guards and executable/cgroup validation. Warm mode
    # uses no cgroup, so the host's cgroup-v2 mount stands in for the checked root.
    bench.arguments(['--java', str(args.java), '--jar', str(args.jar), '--z3', str(args.z3),
                     '--cgroup-root', str(args.cgroup_root or '/sys/fs/cgroup'),
                     '--output-directory',
                     str(args.output_directory / 'validation'), str(args.manifest),
                     *['--jvm-arg=' + t for t in args.jvm_arg],
                     *['--verifier-arg=' + t for t in args.verifier_arg]])
    try:
        execute(args, load_manifest(args.manifest, args.corpus_root))
    except (OSError, ValueError) as exc:
        parser.exit(1, str(exc) + '\n')


if __name__ == '__main__':
    main()
