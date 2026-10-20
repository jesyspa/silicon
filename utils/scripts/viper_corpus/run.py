#!/usr/bin/env python3
"""Run a Viper manifest through the solver CPU benchmark in isolated processes."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import fcntl
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
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
    if measurement.get('timed_out'):
        return 'timeout'
    if measurement.get('errors') or measurement.get('leftover_failure'):
        return 'crash'
    code = measurement.get('returncode')
    if re.search(r'(?i)verification[^\n]*(?:timed out|timeout)', log):
        return 'timeout'
    if re.search(r'(?i)exception in thread|an exception occurred|internal error|'
                 r'java\.lang\.[\w.]+(?:Exception|Error)|verification aborted exceptionally', log):
        return 'crash'
    if code == 0 and measurement.get('success'):
        return 'verified'
    # Silicon also exits 1 for caught internal exceptions. Require a normal
    # verification summary to distinguish those from expected negative tests.
    if code == 1 and re.search(r'(?i)verification failed|\b[1-9][0-9]* errors? found\b|'
                               r'the following errors? (?:was|were) found', log):
        return 'failed'
    return 'crash'


def run_one(row, args):
    relative = Path('inputs') / (row['sha256'] + '-' + uuid.uuid4().hex)
    directory = args.output_directory / relative
    command = [sys.executable, str(Path(bench.__file__).resolve()),
               '--cgroup-root', str(args.cgroup_root), '--java', str(args.java),
               '--jar', str(args.jar), '--z3', str(args.z3),
               '--output-directory', str(directory), '--timeout', str(args.timeout)]
    command += ['--jvm-arg=' + token for token in args.jvm_arg]
    command += ['--verifier-arg=' + token for token in args.verifier_arg]
    command += [str(args.corpus_root / row['path'])]
    directory.parent.mkdir(parents=True, exist_ok=True)
    # The reused benchmark owns timeout, process-group termination, cgroups,
    # signal handling and measurement persistence; threads only schedule it.
    completed = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    measurement_path = directory / 'measurement.json'
    measurement = json.loads(measurement_path.read_text()) if measurement_path.exists() else {}
    log_path = directory / 'stdout.log'
    log = log_path.read_text(errors='replace') if log_path.exists() else ''
    return dict(**row, outcome=outcome(measurement, log),
                solver_cpu_s=measurement.get('solver_cpu_s', {}).get('usage'),
                total_process_cpu_s=measurement.get('total_process_cpu_s'),
                wall_s=measurement.get('elapsed_s'),
                returncode=measurement.get('returncode'),
                measurement=str(relative / 'measurement.json'),
                errors=measurement.get('errors', []) + (
                    [completed.stderr.decode(errors='replace')] if not measurement else []))


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
        with results.open('a') as sink, ThreadPoolExecutor(max_workers=args.jobs) as pool:
            futures = [pool.submit(run_one, row, args) for row in pending]
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


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument('--corpus-root', required=True, type=Path)
    parser.add_argument('--manifest', required=True, type=Path)
    parser.add_argument('--output-directory', required=True, type=Path)
    parser.add_argument('--cgroup-root', required=True, type=Path)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--java', type=Path, default=Path(shutil.which('java') or 'java'))
    parser.add_argument('--z3', type=Path, default=Path(shutil.which('z3') or 'z3'))
    parser.add_argument('--jobs', type=int, default=6, help='parallel input jobs')
    parser.add_argument('--timeout', type=int, default=300, help='per-input wall limit in seconds')
    parser.add_argument('--jvm-arg', action='append', default=[])
    parser.add_argument('--verifier-arg', action='append', default=[])
    args = parser.parse_args(argv)
    if args.jobs <= 0 or args.timeout <= 0:
        parser.error('jobs and timeout must be positive')
    for name in ('corpus_root', 'manifest', 'output_directory', 'cgroup_root', 'jar', 'java', 'z3'):
        setattr(args, name, getattr(args, name).resolve())
    # Reuse the benchmark's option guards and executable/cgroup validation.
    bench.arguments(['--java', str(args.java), '--jar', str(args.jar), '--z3', str(args.z3),
                     '--cgroup-root', str(args.cgroup_root), '--output-directory',
                     str(args.output_directory / 'validation'), str(args.manifest),
                     *['--jvm-arg=' + t for t in args.jvm_arg],
                     *['--verifier-arg=' + t for t in args.verifier_arg]])
    try:
        execute(args, load_manifest(args.manifest, args.corpus_root))
    except (OSError, ValueError) as exc:
        parser.exit(1, str(exc) + '\n')


if __name__ == '__main__':
    main()
