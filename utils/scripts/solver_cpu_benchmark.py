#!/usr/bin/env python3
"""Linux cgroup-v2 accounting for solver CPU only; uses Python's standard library."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid

RESERVED = ('prover', 'z3Exe', 'cvc5Exe', 'timeout', 'tempDirectory', 'proverLogFile')


def arguments(argv=None):
    p = argparse.ArgumentParser(description=__doc__, epilog=(
        'Requires a writable delegated cgroup-v2 root with cpu.stat and cgroup.kill. '
        'Creates dedicated solver and verifier leaves; solver CPU excludes Java CPU. '
        'Also records total process CPU. Writes measurement.json '
        'and merged stdout/stderr to stdout.log. Nonzero exit means '
        'verifier failure, timeout, leftovers, or measurement/cleanup failure. '
        'Use --verifier-arg=--option and --jvm-arg=-Xmx2g for dash-prefixed tokens.'))
    for name in ('cgroup-root', 'java', 'jar', 'z3', 'output-directory'):
        p.add_argument('--' + name, required=True, type=Path)
    p.add_argument('input', type=Path)
    p.add_argument('--timeout', type=int, default=60,
                   help='wall-clock limit in seconds, including JVM startup (default: 60)')
    p.add_argument('--jvm-arg', action='append', default=[], help='one JVM argument; repeatable')
    p.add_argument('--verifier-arg', action='append', default=[],
                   help='one verifier argument/value; repeatable; accounting options reserved')
    p.add_argument('--smt-logs', action='store_true', help='retain SMT logs in output directory')
    p.add_argument('--solver-memory-max', type=int, help='solver leaf memory.max in bytes')
    p.add_argument('--solver-swap-max', type=int, help='solver leaf memory.swap.max in bytes')
    a = p.parse_args(argv)
    if a.timeout <= 0:
        p.error('--timeout must be positive')
    if any(v is not None and v < 0 for v in (a.solver_memory_max, a.solver_swap_max)):
        p.error('solver memory/swap limits must be nonnegative')
    for token in a.verifier_arg:
        key = token.lstrip('-').split('=', 1)[0]
        if token == '--' or token.startswith('@') or (token.startswith('-') and (
                not token.startswith('--') or any(r.startswith(key) for r in RESERVED))):
            p.error('reserved/ambiguous verifier argument: ' + token)
    for token in a.jvm_arg:
        if not token.startswith(('-X', '-D', '-ea', '-da', '-esa', '-dsa', '-agent')):
            p.error('JVM arguments must be VM options (-X, -D, assertions, agents): ' + token)
    for name in ('java', 'jar', 'z3', 'input', 'cgroup_root', 'output_directory'):
        setattr(a, name, getattr(a, name).absolute())
    for name in ('java', 'jar', 'z3', 'input'):
        if not getattr(a, name).is_file():
            p.error(name + ' must be a file')
    for name in ('java', 'z3'):
        if not os.access(getattr(a, name), os.X_OK):
            p.error(name + ' must be executable')
    if not (a.cgroup_root / 'cgroup.controllers').is_file():
        p.error('--cgroup-root must be a delegated cgroup-v2 directory')
    return a


def cpu_stats(cgroup):
    stats = {k: int(v) for k, v in
             (line.split() for line in (cgroup / 'cpu.stat').read_text().splitlines())}
    for key in ('usage_usec', 'user_usec', 'system_usec'):
        if key not in stats:
            raise RuntimeError('cpu.stat missing ' + key)
    return stats


def kill_group(proc):
    try:
        os.killpg(proc.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    proc.wait()


def cleanup(cgroup, record):
    """Read final counters only after every solver has stopped consuming CPU."""
    errors = record['errors']
    try:
        record['leftover_pids'] = sorted(set(record['leftover_pids']) |
                                        set((cgroup / 'cgroup.procs').read_text().split()))
        populated = lambda: 'populated 1' in (cgroup / 'cgroup.events').read_text()
        if populated():
            record['leftover_failure'] = True
            (cgroup / 'cgroup.kill').write_text('1\n')
            deadline = time.monotonic() + 5
            while populated():
                if time.monotonic() >= deadline:
                    raise RuntimeError('solver cgroup still populated after cgroup.kill')
                time.sleep(0.02)
    except Exception as exc:
        errors.append('solver shutdown: ' + repr(exc))
    try:
        record['cpu_stats'] = cpu_stats(cgroup)
        record['solver_cpu_s'] = {k: record['cpu_stats'][k + '_usec'] / 1e6
                                  for k in ('usage', 'user', 'system')}
    except Exception as exc:
        errors.append('CPU accounting: ' + repr(exc))
    try:
        events = cgroup / 'memory.events'
        if events.exists():
            record['memory_events'] = {k: int(v) for k, v in
                                       (line.split() for line in events.read_text().splitlines())}
    except Exception as exc:
        errors.append('memory accounting: ' + repr(exc))
    try:
        cgroup.rmdir()
    except Exception as exc:
        errors.append('cgroup removal: ' + repr(exc))


def version(command):
    proc = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            start_new_session=True)
    try:
        timed_out = False
        try:
            output, _ = proc.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            timed_out = True
            kill_group(proc)
            output, _ = proc.communicate()
        return dict(command=command, returncode=proc.returncode, timed_out=timed_out,
                    output=output.decode(errors='replace'))
    finally:
        kill_group(proc)


def benchmark(a):
    # mkdir is deliberately outside recovery: existing output must never be touched.
    a.output_directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    out = a.output_directory
    cgroup = a.cgroup_root / ('solver-cpu-' + uuid.uuid4().hex)
    process_cgroup = a.cgroup_root / ('verifier-cpu-' + uuid.uuid4().hex)
    wrapper = Path(__file__).with_name('z3-cpu-wrapper.sh').resolve()
    command = [str(a.java), *a.jvm_arg, '-jar', str(a.jar), '--prover', 'Z3',
               '--z3Exe', str(wrapper), '--timeout', str(a.timeout),
               '--tempDirectory', str(out)]
    if a.smt_logs:
        command += ['--proverLogFile', str(out / 'solver')]
    command += [*a.verifier_arg, str(a.input)]
    record = dict(command=command, cgroup=str(cgroup), input=str(a.input),
                  timeout_s=a.timeout, smt_logs=a.smt_logs, returncode=None,
                  elapsed_s=None, timed_out=False, leftover_failure=False,
                  leftover_pids=[], errors=[])
    env = os.environ.copy()
    env.update(SOLVER_CGROUP=str(cgroup), SOLVER_Z3=str(a.z3))
    record.update(cwd=os.getcwd(), platform=list(os.uname()), python=sys.version)
    proc = None
    created = False
    process_created = False
    start = None
    record['total_process_cpu_s'] = None
    handlers = {}

    def interrupted(signum, frame):
        raise KeyboardInterrupt('signal ' + str(signum))

    try:
        for sig in (signal.SIGTERM, signal.SIGINT):
            handlers[sig] = signal.signal(sig, interrupted)
        record['versions'] = {'java': version([str(a.java), '-version']),
                              'z3': version([str(a.z3), '-version']),
                              'verifier': version([str(a.java), *a.jvm_arg, '-jar',
                                                   str(a.jar), '--help'])}
        if any(v.get('timed_out') for v in record['versions'].values()):
            raise RuntimeError('version probe timed out; partial output retained in versions')
        record['artifacts'] = {}
        for name in ('jar', 'input', 'java', 'z3'):
            path = getattr(a, name)
            digest = hashlib.sha256()
            with path.open('rb') as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b''):
                    digest.update(chunk)
            record['artifacts'][name] = dict(path=str(path), sha256=digest.hexdigest())
        cgroup.mkdir()
        created = True
        # Corpus attempts live under <run>/inputs/<attempt>. This optional
        # run-level policy permits operator limit changes between inputs.
        policy_path = a.output_directory.parent.parent / 'solver-limits.json'
        if a.output_directory.parent.name == 'inputs' and policy_path.exists():
            policy = json.loads(policy_path.read_text())
            for option in ('solver_memory_max', 'solver_swap_max'):
                value = policy[option]
                if type(value) is not int or value < 0:
                    raise ValueError('invalid runtime solver limit: ' + option)
                setattr(a, option, value)
            record['solver_limits_policy'] = str(policy_path)
        for option, filename in (('solver_memory_max', 'memory.max'),
                                 ('solver_swap_max', 'memory.swap.max')):
            limit = getattr(a, option, None)
            if limit is not None:
                target = cgroup / filename
                if not target.exists():
                    raise RuntimeError('enable the delegated memory controller before setting ' + filename)
                target.write_text(str(limit) + '\n')
                record[option] = limit
        cpu_stats(cgroup)
        if not os.access(cgroup / 'cgroup.procs', os.W_OK) or not os.access(
                cgroup / 'cgroup.kill', os.W_OK):
            raise RuntimeError('delegation requires writable cgroup.procs and cgroup.kill')
        process_cgroup.mkdir()
        process_created = True
        cpu_stats(process_cgroup)

        def enter_process_cgroup():
            # benchmark() runs in a dedicated, single-threaded CLI process.
            # Solvers subsequently move to their separately accounted leaf.
            (process_cgroup / 'cgroup.procs').write_text(str(os.getpid()) + '\n')

        with (out / 'stdout.log').open('wb') as log:
            start = time.monotonic()
            proc = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT,
                                    start_new_session=True, preexec_fn=enter_process_cgroup)
            try:
                proc.wait(timeout=a.timeout)
            except subprocess.TimeoutExpired:
                record['timed_out'] = True
    except (Exception, KeyboardInterrupt) as exc:
        record['errors'].append(repr(exc))
    finally:
        for sig in handlers:
            signal.signal(sig, signal.SIG_IGN)
        try:
            if proc is not None:
                try:
                    record['leftover_pids'] = (cgroup / 'cgroup.procs').read_text().split()
                    record['leftover_failure'] = bool(record['leftover_pids'])
                except Exception as exc:
                    record['errors'].append('leftover detection: ' + repr(exc))
                try:
                    kill_group(proc)
                    record['returncode'] = proc.returncode
                except Exception as exc:
                    record['errors'].append('verifier shutdown: ' + repr(exc))
            if start is not None:
                record['elapsed_s'] = time.monotonic() - start
            if process_created:
                process_record = dict(errors=[], leftover_pids=[], leftover_failure=False)
                cleanup(process_cgroup, process_record)
                record['process_cpu_stats'] = process_record.get('cpu_stats')
                record['process_memory_events'] = process_record.get('memory_events', {})
                record['errors'].extend('verifier cgroup: ' + e for e in process_record['errors'])
                record['leftover_failure'] |= process_record['leftover_failure']
            if created:
                cleanup(cgroup, record)
            if (record.get('process_cpu_stats') is not None
                    and record.get('cpu_stats') is not None):
                record['total_process_cpu_s'] = (
                    record['process_cpu_stats']['usage_usec']
                    + record['cpu_stats']['usage_usec']) / 1e6
            record['success'] = (record['returncode'] == 0 and not record['timed_out']
                                 and not record['leftover_failure'] and not record['errors'])
            (out / 'measurement.json').write_text(json.dumps(record, indent=2) + '\n')
        finally:
            for sig, handler in handlers.items():
                signal.signal(sig, handler)
    print(json.dumps(record))
    return 0 if record['success'] else 1


def main(argv=None):
    try:
        return benchmark(arguments(argv))
    except OSError as exc:
        print(str(exc), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
