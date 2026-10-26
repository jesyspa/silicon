import argparse
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import analyze
import compare
import curate
import night
import noise
import run
import tiers


class CorpusTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.corpus = self.root / 'corpus'
        self.corpus.mkdir()

    def source(self, name, text):
        path = self.corpus / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def test_curate_deduplicates_and_excludes_preambles_before_hashing(self):
        self.source('A/tool/suite/a.vpr', 'same')
        self.source('B/tool/suite/b.vpr', 'same')
        self.source('A/tool/_preambles/import.vpr', 'unique import')
        self.source('A/tool/_preambles/duplicate.vpr', 'other')
        self.source('C/tool/c.vpr', 'other')
        rows = curate.curate(self.corpus)
        self.assertEqual([r['path'] for r in rows], ['A/tool/suite/a.vpr', 'C/tool/c.vpr'])
        self.assertEqual(rows[0]['size'], 4)
        self.assertEqual(rows[0]['group'], 'A')
        self.assertEqual(rows[0]['sha256'], curate.sha256(self.corpus / rows[0]['path']))

    def test_quick_is_deterministic_balanced_and_includes_largest(self):
        rows = [dict(path=f'{g}/{i:03}.vpr', group=g, size=i)
                for g, count in [('small', 3), ('big', 100), ('other', 50)]
                for i in range(count)]
        subset = curate.quick_subset(rows, target=25, cap=5, seed=4)
        self.assertEqual(len(subset), 25)
        self.assertEqual(subset, curate.quick_subset(list(reversed(rows)), 25, 5, 4))
        paths = {r['path'] for r in subset}
        self.assertTrue({'small/000.vpr', 'small/001.vpr', 'small/002.vpr',
                         'big/099.vpr', 'other/049.vpr'} <= paths)
        with self.assertRaises(ValueError):
            curate.quick_subset(rows, target=2)
        self.assertEqual(curate.quick_subset(rows, target=1000), sorted(rows, key=lambda r: r['path']))

    def manifest(self):
        self.source('A/tool/a.vpr', 'method test() {}')
        manifest = self.root / 'manifest.jsonl'
        rows = curate.curate(self.corpus)
        curate.write_manifest(manifest, rows)
        return manifest, rows

    def test_manifest_checks_hash_traversal_and_symlink_escape(self):
        manifest, rows = self.manifest()
        self.assertEqual(run.load_manifest(manifest, self.corpus), rows)
        self.source(rows[0]['path'], 'changed')
        with self.assertRaises(ValueError):
            run.load_manifest(manifest, self.corpus)
        for name in ('../escape.vpr', '/A/escape.vpr', 'B/a.vpr'):
            bad = dict(rows[0], path=name)
            curate.write_manifest(manifest, [bad])
            with self.assertRaises(ValueError):
                run.load_manifest(manifest, self.corpus)
        outside = self.root / 'outside.vpr'
        outside.write_text('outside')
        link = self.corpus / 'A/link.vpr'
        link.symlink_to(outside)
        curate.write_manifest(manifest, [dict(rows[0], path='A/link.vpr')])
        with self.assertRaises(ValueError):
            run.load_manifest(manifest, self.corpus)

    def test_outcomes(self):
        base = dict(returncode=0, success=True, errors=[], leftover_failure=False)
        self.assertEqual(run.outcome(base, ''), 'verified')
        self.assertEqual(run.outcome(dict(base, timed_out=True), ''), 'timeout')
        for key in ('memory_events', 'process_memory_events'):
            self.assertEqual(run.outcome(dict(base, **{key: {'oom_kill': 1}}), ''), 'oom')
        self.assertEqual(run.outcome(dict(base, returncode=1, success=False),
                                     'Verification failed with 2 errors'), 'failed')
        self.assertEqual(run.outcome(dict(base, returncode=1), '2 errors found'), 'failed')
        for summary in ('Silicon found 1 error in 1.72s:', 'Silicon found 3 errors in 2.06s:'):
            self.assertEqual(run.outcome(dict(base, returncode=1), summary), 'failed')
        self.assertEqual(run.outcome(dict(base, returncode=1),
                                     'Silicon found 1 error: An exception occurred'), 'crash')
        self.assertEqual(run.outcome(dict(base, returncode=1),
                                     'The following errors were found:'), 'failed')
        self.assertEqual(run.outcome(dict(base, returncode=1),
                                     'The following errors were found: An exception occurred'), 'crash')
        self.assertEqual(run.outcome(dict(base, returncode=1), 'Verification timed out'), 'timeout')
        self.assertEqual(run.outcome(dict(base, returncode=1),
                                     'Verification aborted due to a timeout after 1 second'), 'timeout')
        self.assertEqual(run.outcome(dict(base, returncode=1), 'Silicon found 1 error in 02m:00s:\n'
                                     '  [0] Timeout occurred after 120 second(s)'), 'timeout')
        self.assertEqual(run.outcome(dict(base, errors=['accounting failed']), ''), 'crash')
        self.assertEqual(run.outcome(dict(base, returncode=-9), ''), 'crash')
        self.assertEqual(run.outcome(dict(base, returncode=1), 'Exception'), 'crash')
        self.assertEqual(run.outcome({}, ''), 'crash')

    def args(self):
        manifest, rows = self.manifest()
        binary = self.root / 'binary'
        binary.write_text('binary')
        return argparse.Namespace(manifest=manifest, corpus_root=self.corpus,
                                  output_directory=self.root / 'output', timeout=300,
                                  java=binary, jar=binary, z3=binary, cgroup_root=self.root,
                                  jvm_arg=[], verifier_arg=[], jobs=2, warm=False,
                                  warm_runs=1), rows

    def test_resume_skips_completed_repairs_tail_and_rejects_configuration_change(self):
        args, rows = self.args()
        result = dict(rows[0], outcome='verified', solver_cpu_s=1, wall_s=2)
        with patch.object(run, 'run_one', return_value=result) as worker, \
                contextlib.redirect_stdout(io.StringIO()):
            run.execute(args, rows)
            results = args.output_directory / 'results.jsonl'
            with results.open('ab') as sink:
                sink.write(b'{"path":')
            run.execute(args, rows)
            worker.assert_called_once()
            self.assertEqual(run.read_jsonl(results), [result])
            args.timeout += 1
            with self.assertRaises(ValueError):
                run.execute(args, rows)

    def test_lock_prevents_concurrent_writers(self):
        args, rows = self.args()
        args.output_directory.mkdir()
        with (args.output_directory / '.lock').open('w') as lock:
            run.fcntl.flock(lock, run.fcntl.LOCK_EX | run.fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError):
                run.execute(args, rows)

    def test_subprocess_reuses_benchmark_and_flattens_measurement(self):
        args, rows = self.args()
        rows[0]['verifier_args'] = ['--assumeInjectivityOnInhale']

        def invoke(command, **kwargs):
            self.assertEqual(Path(command[1]), Path(run.bench.__file__).resolve())
            self.assertEqual(command[-1], str(self.corpus / rows[0]['path']))
            self.assertIn('--verifier-arg=--assumeInjectivityOnInhale', command)
            out = Path(command[command.index('--output-directory') + 1])
            out.mkdir()
            (out / 'measurement.json').write_text(json.dumps(dict(
                returncode=0, success=True, errors=[], solver_cpu_s=dict(usage=1.25),
                total_process_cpu_s=2.5, elapsed_s=3.75)))
            (out / 'stdout.log').write_text('verified')
            return subprocess.CompletedProcess(command, 0, stderr=b'')

        with patch.object(run.subprocess, 'run', side_effect=invoke):
            result = run.run_one(rows[0], args)
        self.assertEqual(result['outcome'], 'verified')
        self.assertEqual(result['solver_cpu_s'], 1.25)
        self.assertEqual(result['total_process_cpu_s'], 2.5)
        self.assertEqual(result['wall_s'], 3.75)
        self.assertTrue((args.output_directory / result['measurement']).is_file())

    def test_missing_measurement_is_crash(self):
        args, rows = self.args()
        with patch.object(run.subprocess, 'run', return_value=subprocess.CompletedProcess(
                [], 1, stderr=b'worker failed')):
            result = run.run_one(rows[0], args)
        self.assertEqual(result['outcome'], 'crash')
        self.assertIsNone(result['solver_cpu_s'])
        self.assertEqual(result['errors'], ['worker failed'])

    def test_warm_lane_classifies_restarts_and_keeps_warmup(self):
        # A fake JVM: behaviour is chosen by the input name; 'hang' sleeps past the deadline.
        java = self.root / 'java'
        events = self.root / 'memory.events'
        events.write_text('oom 0\noom_kill 0\n')
        java.write_text('#!' + sys.executable + f'\nevents = {str(events)!r}\n' + '''
import json, os, sys, time
print(json.dumps(dict(ready=True)), flush=True)
for line in sys.stdin:
    log, *options = line.rstrip('\\n').split('\\t')
    name = os.path.basename(options[-1])
    if name == 'oom.vpr': sys.exit(3)
    if name == 'hang.vpr': time.sleep(60)
    if name == 'die.vpr': sys.exit(1)
    if name == 'partial.vpr':
        sys.stdout.write('{"success":'); sys.stdout.flush(); time.sleep(60)
    if name == 'invalid.vpr':
        print('not json', flush=True); continue
    if name == 'schema.vpr':
        print('{"ready":true}', flush=True); continue
    if name == 'truncated.vpr':
        sys.stdout.write('{"success":'); sys.stdout.flush(); sys.exit(1)
    if name.startswith('z3oom'):  # the kernel killed a solver; the runner survives
        kills = int(open(events).read().split()[-1]) + 1
        open(events, 'w').write(f'oom {kills}\\noom_kill {kills}\\n')
        if name == 'z3oom-hang.vpr': time.sleep(60)
    open(log, 'w').write('Silicon found 1 error' if name == 'bad.vpr' else 'ok')
    print(json.dumps(dict(success=name == 'a.vpr', wall_s=0.5, jvm_cpu_ticks=200,
                          children_cpu_ticks=100, heap_after_gc_bytes=7,
                          gc={'G1 Young Generation': dict(count=2, ms=250),
                              'G1 Concurrent GC': dict(count=1, ms=900)},
                          fatal=name == 'fatal.vpr')), flush=True)
''')
        java.chmod(0o755)
        args, rows = self.args()
        args.java, args.warm, args.jobs, args.timeout = java, True, 1, 1
        run.HANG_GRACE_S = 0.5
        self.addCleanup(setattr, run, 'HANG_GRACE_S', 30)
        scope = patch.object(run.Lane, 'scope', return_value=[])
        cgroup = patch.object(run, 'process_cgroup', return_value=self.root)
        scope.start(), cgroup.start()
        self.addCleanup(scope.stop)
        self.addCleanup(cgroup.stop)
        names = ['a', 'bad', 'oom', 'a', 'hang', 'fatal', 'a', 'die', 'a', 'z3oom', 'a',
                 'z3oom-hang', 'a']
        rows = [dict(rows[0], path=f'A/{i}/{n}.vpr') for i, n in enumerate(names)]
        lane = run.Lane(args, 0, java)
        self.addCleanup(lane.stop)
        results = [run.run_warm_once(row, args, lane) for row in rows]
        self.assertEqual([r['outcome'] for r in results],
                         ['verified', 'failed', 'oom', 'verified', 'timeout', 'crash',
                          'verified', 'crash', 'verified', 'oom', 'verified', 'oom', 'verified'])
        self.assertEqual([r['runner_start'] for r in results],
                         [1, 1, 1, 2, 2, 3, 4, 4, 5, 5, 6, 6, 7])
        self.assertEqual([r['runner_served'] for r in results],
                         [0, 1, 2, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0])
        self.assertEqual(results[9]['wall_s'], 0.5)
        self.assertFalse(results[0]['after_restart'])
        self.assertTrue(results[3]['after_restart'])
        tick = os.sysconf('SC_CLK_TCK')
        self.assertEqual(results[0]['solver_cpu_s'], 100 / tick)
        self.assertEqual(results[0]['total_process_cpu_s'], 300 / tick)
        self.assertIsNone(results[2]['wall_s'])
        self.assertEqual(results[0]['gc_s'], 0.25)
        self.assertEqual(results[0]['gc']['G1 Concurrent GC'], dict(count=1, ms=900))
        self.assertIsNone(results[2]['gc_s'])
        for name, expected in [('partial', 'timeout'), ('invalid', 'crash'),
                               ('schema', 'crash'), ('truncated', 'crash')]:
            with self.subTest(name=name):
                broken = run.run_warm_once(dict(rows[0], path=f'A/{name}.vpr'), args, lane)
                self.assertEqual(broken['outcome'], expected)
                self.assertIsNone(lane.process)
                recovered = run.run_warm_once(rows[0], args, lane)
                self.assertEqual(recovered['outcome'], 'verified')
                self.assertEqual(recovered['runner_start'], broken['runner_start'] + 1)
                self.assertEqual(recovered['runner_served'], 0)
        args.warm_runs = 2
        lanes = run.queue.Queue()
        lanes.put(run.Lane(args, 1, java))
        self.addCleanup(lanes.queue[0].stop)
        record = run.run_warm(rows[0], args, lanes)
        self.assertEqual(record['runner_served'], 1)
        self.assertEqual([w['runner_served'] for w in record['warmup']], [0])

    def test_runner_reader_bounds_partial_lines_by_one_deadline(self):
        args, _ = self.args()
        lane = run.Lane(args, 0, args.jar)
        lane.process = Mock()
        stdout = lane.process.stdout
        # Readiness promises bytes, not a complete JSON line. A second fragment
        # must use the original deadline rather than granting another timeout.
        with patch.object(run.time, 'monotonic', side_effect=[10, 10, 11, 12]), \
                patch.object(run.select, 'select', side_effect=[([stdout], [], []),
                                                                ([stdout], [], []), ([], [], [])]) as select, \
                patch.object(run.os, 'read', side_effect=[b'{"success":', b'true']) as read:
            self.assertIsNone(lane.read(3))
        self.assertEqual(read.call_count, 2)
        self.assertEqual([call.args[-1] for call in select.call_args_list], [3, 2, 1])
        stdout.readline.assert_not_called()

    def test_runner_reader_rejects_unframed_or_invalid_responses(self):
        args, _ = self.args()
        lane = run.Lane(args, 0, args.jar)
        lane.process = Mock()
        for payload in (b'not json\n', b'[]\n', b'{}\n', b'\xff\n',
                        b'{"ready":true}\n{"ready":true}\n', b'x' * 33):
            with self.subTest(payload=payload), \
                    patch.object(run, 'MAX_RESPONSE_BYTES', 32), \
                    patch.object(run.select, 'select', return_value=([lane.process.stdout], [], [])), \
                    patch.object(run.os, 'read', return_value=payload):
                with self.assertRaises(run.RunnerProtocolError):
                    lane.read(1)
        with patch.object(run.select, 'select', return_value=([lane.process.stdout], [], [])), \
                patch.object(run.os, 'read', side_effect=[b'{"ready":', b'true}\n']):
            self.assertEqual(lane.read(1), {'ready': True})
        with patch.object(run.select, 'select', return_value=([lane.process.stdout], [], [])), \
                patch.object(run.os, 'read', side_effect=[b'{"ready":', b'']):
            with self.assertRaises(run.RunnerProtocolError):
                lane.read(1)

    def test_runner_send_bounds_blocked_writes_and_rejects_request_injection(self):
        args, _ = self.args()
        lane = run.Lane(args, 0, args.jar)
        lane.process = Mock()
        stdin = lane.process.stdin
        with patch.object(run.time, 'monotonic', side_effect=[10, 11]), \
                patch.object(run.select, 'select', side_effect=[([], [stdin], []), ([], [], [])]), \
                patch.object(run.os, 'write', return_value=2) as write:
            self.assertFalse(lane.send('log', ['input.vpr'], 12))
            self.assertEqual(write.call_count, 1)
        for value in ('a\tb', 'a\nb', 'a\rb'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                lane.send('log', [value], 12)
        stdin.write.assert_not_called()

    def test_runner_response_schema_rejects_unusable_measurements(self):
        response = dict(success=True, fatal=False, wall_s=0.5, jvm_cpu_ticks=2,
                        children_cpu_ticks=1, heap_after_gc_bytes=7)
        run.validate_response(response)  # GC is optional for older runners.
        for key, value in [('success', 1), ('fatal', None), ('wall_s', float('nan')),
                           ('wall_s', float('inf')), ('wall_s', -1), ('jvm_cpu_ticks', True),
                           ('children_cpu_ticks', 0.5), ('heap_after_gc_bytes', -1),
                           ('gc', []), ('gc', {'collector': {'count': 1}})]:
            with self.subTest(key=key, value=value), self.assertRaises(run.RunnerProtocolError):
                run.validate_response(dict(response, **{key: value}))
        with self.assertRaises(run.RunnerProtocolError):
            run.validate_response({'ready': True})

    def test_night_failed_warmup_discards_runner_before_any_measured_request(self):
        args, _ = self.args()
        args.output_directory.mkdir()
        for failure in ('timeout', 'crash', 'oom'):
            with self.subTest(failure=failure):
                lane = night.NightLane(args, 0, args.jar, warmup=[['warm.vpr']])
                # verify() starts the runner, but start() must not return a lane
                # whose warm-up response could later be read as measured.vpr.
                with patch.object(run.Lane, 'start'), \
                        patch.object(lane, 'request', return_value=(None, failure)) as request, \
                        patch.object(lane, 'stop') as stop:
                    with self.assertRaises(night.WarmupFailure):
                        lane.verify(self.root / 'measured.log', ['measured.vpr'])
                    self.assertEqual(request.call_count, 2)
                    self.assertTrue(all(call.args[1] == ['warm.vpr'] for call in request.call_args_list))
                    self.assertEqual(stop.call_count, 3)  # before start and after each failed attempt
        self.assertFalse((args.output_directory / 'warmup' / 'wall.jsonl').exists())

    def test_night_retries_warmup_on_a_fresh_runner(self):
        args, _ = self.args()
        args.output_directory.mkdir()
        response = dict(success=True, fatal=False, wall_s=0.5)
        failures = [(None, 'timeout'), (dict(response, success=False), None),
                    (dict(response, success=False, fatal=True), 'crash')]
        for first in failures:
            with self.subTest(first=first):
                lane = night.NightLane(args, 0, args.jar, warmup=[['warm.vpr']])
                with patch.object(run.Lane, 'start') as start, \
                        patch.object(lane, 'request', side_effect=[first, (response, None)]) as request, \
                        patch.object(lane, 'stop') as stop:
                    lane.start()
                self.assertEqual(start.call_count, 2)
                self.assertEqual(stop.call_count, 1)
                self.assertEqual(request.call_count, 2)
                wall = args.output_directory / 'warmup' / 'wall.jsonl'
                self.assertEqual(run.read_jsonl(wall)[-1], dict(runner='0', wall_s=0.5))
                wall.unlink()

    def test_night_dead_lane_requeues_work_for_an_idle_survivor(self):
        args, rows = self.args()
        args.manifest = [args.manifest]
        args.seeds, args.order_seed, args.lane_memory_max, args.slice = [0], 0, '1G', 'test.slice'
        args.warmup, args.warmup_runs, args.window, args.lanes = [], 1, None, ['0', '1']
        jar = args.jar
        arms = [dict(name='M', jar=jar, jvm_arg=[], verifier_arg=[])]
        del args.jar
        rows += [dict(rows[0], path='A/other.vpr')]
        first_entered, survivor_finished = night.threading.Event(), night.threading.Event()
        calls = []

        def measure(row, task, lane):
            if lane.index == 0:
                first_entered.set()
                self.assertTrue(survivor_finished.wait(5))
                raise night.WarmupFailure('warm-up failed twice')
            self.assertTrue(first_entered.wait(5))
            calls.append(row['path'])
            survivor_finished.set()
            return dict(outcome='verified', wall_s=1)

        with patch.object(run, 'build_runner', return_value=jar), \
                patch.object(run, 'run_warm_once', side_effect=measure):
            night.execute(args, arms, [rows])
        records = run.read_jsonl(args.output_directory / 'results.jsonl')
        self.assertEqual(sorted(calls), sorted(row['path'] for row in rows))
        self.assertEqual(len(records), 2)
        self.assertEqual({row['cpus'] for row in records}, {'1'})
        self.assertIn('lane 0 dead', (args.output_directory / 'night.log').read_text())

        # If every lane dies, leave all work resumable and exit nonzero.
        args.output_directory = self.root / 'all-dead'
        with patch.object(run, 'build_runner', return_value=jar), \
                patch.object(run, 'run_warm_once', side_effect=night.WarmupFailure('failed twice')):
            with self.assertRaisesRegex(OSError, 'all lanes failed'):
                night.execute(args, arms, [rows])
        self.assertEqual(run.read_jsonl(args.output_directory / 'results.jsonl'), [])

    def test_seed_joins_the_single_prover_arguments_string(self):
        args, rows = self.args()
        row = dict(rows[0], verifier_args=['--assumeInjectivityOnInhale'])
        self.assertEqual(run.verifier_args(row, args), ['--assumeInjectivityOnInhale'])
        args.seed = 2
        seeds = 'smt.random_seed=2 sat.random_seed=2'
        self.assertEqual(run.verifier_args(row, args),
                         ['--assumeInjectivityOnInhale', '--proverArgs', seeds])
        args.verifier_arg = ['--proverArgs', 'memory_max_size=3000', '--timeout', '9']
        self.assertEqual(run.verifier_args(row, args),
                         ['--proverArgs', 'memory_max_size=3000 ' + seeds, '--timeout', '9',
                          '--assumeInjectivityOnInhale'])
        args.verifier_arg = ['--proverArgs=memory_max_size=3000']
        self.assertEqual(run.verifier_args(rows[0], args),
                         ['--proverArgs=memory_max_size=3000 ' + seeds])

    def test_compare_pairs_outcomes_missing_inputs_and_rankings(self):
        def row(path, cpu, wall, outcome='verified', sha='hash'):
            return dict(path=path, group='A', sha256=sha, solver_cpu_s=cpu,
                        wall_s=wall, outcome=outcome)
        old = {p: row(p, 1, 4) for p in ('a', 'b', 'c', 'd', 'e', 'only-old', 'zero')}
        new = {p: row(p, 1, 4) for p in ('a', 'b', 'c', 'd', 'e', 'only-new', 'zero')}
        new['a']['solver_cpu_s'] = 4
        new['b']['solver_cpu_s'] = 0.25
        new['c']['outcome'] = 'timeout'
        new['d']['sha256'] = 'different'
        new['e']['wall_s'] = float('nan')
        old['zero']['solver_cpu_s'] = 0
        report = compare.compare(old, new)
        self.assertEqual(report['baseline_only'], ['only-old'])
        self.assertEqual(report['candidate_only'], ['only-new'])
        self.assertEqual(report['changed_inputs'], ['d'])
        group = report['groups']['A']
        self.assertEqual(group['outcome_changes'], {'verified -> timeout': 1})
        cpu = group['metrics']['solver_cpu_s']
        self.assertAlmostEqual(cpu['geometric_mean_ratio'], 1)
        self.assertEqual(cpu['pairs'], 3)
        self.assertEqual(cpu['regressions'][0]['path'], 'a')
        self.assertEqual(cpu['improvements'][0]['path'], 'b')
        self.assertEqual(group['metrics']['wall_s']['pairs'], 3)
        old['a']['outcome'] = new['a']['outcome'] = 'failed'
        verified = compare.compare(old, new, verified_only=True)['groups']['A']
        self.assertEqual(verified['metrics']['solver_cpu_s']['pairs'], 2)
        self.assertEqual(verified['outcome_changes'], {'verified -> timeout': 1})
        self.assertEqual(compare.ratio(old['a'], new['a'], 'solver_cpu_s'), 4)
        self.assertIsNone(compare.ratio(old['a'], new['a'], 'solver_cpu_s', verified_only=True))
        self.assertIsNone(compare.ratio(row('x', 1, 1, 'timeout'),
                                        row('x', 2, 2, 'timeout'), 'solver_cpu_s'))

    def test_compare_empty_and_zero_pairs(self):
        self.assertEqual(compare.compare({}, {})['groups'], {})
        row = dict(path='x', group='A', sha256='h', outcome='crash')
        metrics = compare.compare({'x': row}, {'x': row})['groups']['A']['metrics']
        self.assertIsNone(metrics['wall_s']['geometric_mean_ratio'])

    def test_night_plan_runs_every_arm_and_seed_of_a_file_together(self):
        a = [dict(path=f'A/{i}.vpr') for i in range(4)]
        b = [dict(path='A/1.vpr'), dict(path='B/x.vpr')]
        tasks = night.plan([a, b], ['M', 'W'], [0, 1, 2], 7)
        self.assertEqual(tasks, night.plan([a, b], ['M', 'W'], [0, 1, 2], 7))
        self.assertEqual(len(tasks), 5 * 6)
        blocks = [tasks[i:i + 6] for i in range(0, len(tasks), 6)]
        for block in blocks:
            self.assertEqual(len({row['path'] for row, _, _ in block}), 1)
            self.assertEqual(sorted((arm, seed) for _, arm, seed in block),
                             [(arm, seed) for arm in 'MW' for seed in range(3)])
        self.assertEqual(blocks[-1][0][0]['path'], 'B/x.vpr')
        self.assertGreater(len({tuple((arm, seed) for _, arm, seed in b) for b in blocks}), 1)

    def test_night_arm_and_window_parsing(self):
        arm = night.parse_arm('W=/x/silicon.jar --assertTimeout 9 -J-Dk=v')
        self.assertEqual((arm['name'], arm['jar'], arm['verifier_arg'], arm['jvm_arg']),
                         ('W', Path('/x/silicon.jar'), ['--assertTimeout', '9'], ['-Dk=v']))
        with self.assertRaises(ValueError):
            night.parse_arm('/x/silicon.jar')
        window = night.parse_window('19:00-08:00')
        at = lambda hour: night.datetime.datetime(2026, 10, 25, hour, 30)
        self.assertEqual([night.in_window(window, at(h)) for h in (7, 8, 18, 19, 23)],
                         [True, False, False, True, True])
        self.assertTrue(night.in_window(night.parse_window('any'), at(12)))

    def test_night_records_arm_and_seed_and_resumes(self):
        # A fake JVM that logs its JAR and the request options.
        java = self.root / 'java'
        java.write_text('#!' + sys.executable + '''
import json, sys
jar = sys.argv[sys.argv.index('-cp') + 1].split(':')[1]
print(json.dumps(dict(ready=True)), flush=True)
for line in sys.stdin:
    log, *options = line.rstrip('\\n').split('\\t')
    open(log, 'w').write(jar + '\\n' + ' '.join(options))
    print(json.dumps(dict(success=True, wall_s=0.5, jvm_cpu_ticks=2, children_cpu_ticks=1,
                          heap_after_gc_bytes=7, fatal=False)), flush=True)
''')
        java.chmod(0o755)
        manifest, _ = self.manifest()
        self.source('A/tool/b.vpr', 'method other() {}')
        rows = curate.curate(self.corpus)
        curate.write_manifest(manifest, rows)
        jars = {}
        for name in 'MW':
            jars[name] = self.root / f'{name}.jar'
            jars[name].write_text(name)
        out = self.root / 'night'
        argv = ['--corpus-root', str(self.corpus), '--manifest', str(manifest),
                '--output-directory', str(out), '--java', str(java), '--z3', str(java),
                '--arm', f'M={jars["M"]}', '--arm', f'W={jars["W"]} --assertTimeout 9',
                '--seeds', '0,2', '--window', 'any', '--warmup', rows[0]['path'],
                '--lanes', ';'.join([str(min(os.sched_getaffinity(0)))] * 2),
                '--verifier-arg=--proverArgs', '--verifier-arg=memory_max_size=3000']
        with patch.object(run.Lane, 'scope', return_value=[]), \
                patch.object(run, 'process_cgroup', return_value=self.root), \
                patch.object(run, 'oom_kills', return_value=0), \
                patch.object(run, 'build_runner', return_value=self.root / 'runner.jar'):
            night.main(argv)
            results = run.read_jsonl(out / 'results.jsonl')
            self.assertEqual(sorted((r['path'], r['arm'], r['seed']) for r in results),
                             [(r['path'], arm, seed) for r in rows for arm in 'MW' for seed in (0, 2)])
            for r in results:
                jar, options = (out / r['measurement']).with_name('stdout.log').read_text().split('\n')
                self.assertEqual(jar, str(jars[r['arm']]))
                self.assertIn(f'memory_max_size=3000 smt.random_seed={r["seed"]} '
                              f'sat.random_seed={r["seed"]}', options)
                self.assertEqual('--assertTimeout 9' in options, r['arm'] == 'W')
                self.assertEqual(r['outcome'], 'verified')
                self.assertGreater(r['jvm_peak_rss_bytes'], 0)
            # One warm-up per started runner, that is per lane and arm used.
            started = {(r['lane'], r['arm']) for r in results}
            self.assertEqual(len(list((out / 'warmup').glob('*.log'))), len(started))
            self.assertEqual(len(run.read_jsonl(out / 'warmup' / 'wall.jsonl')), len(started))
            night.main(argv)
            self.assertEqual(len(run.read_jsonl(out / 'results.jsonl')), len(results))
            with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
                night.main([*argv, '--timeout', '9'])
            with patch.object(night.NightLane, 'start', side_effect=OSError('warm-up failed')), \
                    self.assertRaises(SystemExit) as failed, contextlib.redirect_stderr(io.StringIO()):
                night.main([*argv, '--output-directory', str(self.root / 'failed-night')])
            self.assertEqual(failed.exception.code, 1)
            self.assertEqual(run.read_jsonl(self.root / 'failed-night' / 'results.jsonl'), [])

    def test_analyze_medians_instability_outcomes_and_gc(self):
        out = self.root / 'night'
        (out / 'gc').mkdir(parents=True)

        def record(path, arm, seed, wall, outcome='verified'):
            return dict(path=path, arm=arm, seed=seed, outcome=outcome, wall_s=wall, gc_s=0.1)

        rows = []
        for seed, (m, w) in enumerate([(10, 5), (12, 6), (11, 7)]):
            rows += [record('stable', 'M', seed, m), record('stable', 'W', seed, w),
                     record('unstable', 'M', seed, [1, 5, 1][seed]), record('unstable', 'W', seed, 1),
                     record('flips', 'M', seed, 1),
                     record('flips', 'W', seed, 1, 'timeout' if seed == 1 else 'verified')]
        (out / 'results.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in rows))
        (out / 'gc' / 'W-lane0-1.log').write_text(
            '[1.0s] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 9M->1M(64M) 300.000ms\n'
            '[2.0s] GC(1) Pause Full (System.gc()) 9M->1M(64M) 900.000ms\n')
        (out / 'warmup').mkdir()
        (out / 'warmup' / 'wall.jsonl').write_text('{"runner": "W-lane0", "wall_s": 6}\n')
        report = analyze.analyze([out], 'M', ['wall_s'], 2.0, 200, [('one', {'stable'})])
        c = report['subsets']['all']['comparisons']['W']['wall_s']
        self.assertEqual((c['n'], c['excluded']), (1, 1))
        self.assertAlmostEqual(c['geomean'], 6 / 11)
        self.assertEqual(c['ci95'], [6 / 11, 6 / 11])
        self.assertEqual([u['path'] for u in c['unstable']], ['unstable'])
        self.assertEqual(report['subsets']['one']['comparisons']['W']['wall_s']['n'], 1)
        o = report['outcomes']
        self.assertEqual(o['table']['W'][1], {'verified': 2, 'timeout': 1})
        self.assertEqual([(x['path'], x['arm']) for x in o['across_seeds']], [('flips', 'W')])
        self.assertEqual([(x['path'], x['seed']) for x in o['across_arms']], [('flips', 1)])
        self.assertAlmostEqual(report['gc']['W']['log_pause_s'], 0.3)
        self.assertAlmostEqual(report['gc']['W']['log_share'], 0.3 / 30)
        self.assertIn('| W | wall_s | 1 |', analyze.markdown(report))

    def night_args(self, rows, **extra):
        args, _ = self.args()
        args.manifest, args.order_seed, args.lane_memory_max, args.slice = [args.manifest], 0, '1G', 'test.slice'
        args.warmup, args.warmup_runs, args.window, args.lanes = [], 1, None, ['0']
        args.seeds, args.profile, args.targeted = [0, 1], None, []
        args.baseline, args.cap_factor, args.cap_min = 'M', 4.0, 30
        args.extend_width, args.max_seeds = None, 10
        vars(args).update(extra)
        arms = [dict(name=n, jar=args.jar, jvm_arg=[], verifier_arg=[]) for n in 'MW']
        del args.jar
        return args, arms

    def test_noise_profile_classes_per_jar_and_file(self):
        out = self.root / 'n1'
        out.mkdir()
        (out / 'run.json').write_text(json.dumps(dict(arms=dict(M=dict(sha256='m'),
                                                                W=dict(sha256='w')))))
        rows = []
        walls = dict(quiet=(10, 11), mid=(10, 15), noisy=(1, 3), cut=(5, 6))
        for path, (x, y) in walls.items():
            rows += [dict(path=path, arm='M', seed=0, outcome='verified', wall_s=x),
                     dict(path=path, arm='M', seed=1, outcome='verified', wall_s=y)]
        rows += [dict(path='quiet', arm='W', seed=s, outcome='verified', wall_s=30) for s in (0, 1)]
        rows += [dict(path='flip', arm='M', seed=s, outcome=o, wall_s=9)
                 for s, o in enumerate(['verified', 'timeout'])]
        rows += [dict(path='slow', arm=a, seed=s, outcome='timeout', wall_s=301)
                 for a in 'MW' for s in (0, 1)]
        rows += [dict(path='cut', arm='W', seed=s, outcome='capped', wall_s=24) for s in (0, 1)]
        (out / 'results.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in rows) + '{"part')
        files = noise.profile([out])['files']
        self.assertEqual({p: f['class'] for p, f in files.items()},
                         dict(quiet='quiet', mid='mid', noisy='noisy', flip='flips',
                              slow='always-timeout', cut='quiet'))
        self.assertEqual(files['quiet']['median_wall_s'], (10.5 + 30) / 2)
        self.assertEqual(set(files['cut']['jars']), {'m'})
        # Pooled: a quiet and a noisy JAR of two seeds each.
        self.assertAlmostEqual(files['quiet']['pooled_spread'],
                               noise.math.exp(noise.D2 * noise.math.log(11 / 10) / 2))
        self.assertEqual(noise.file_class(['always-timeout', 'mid'], 1.5, 1.25, 2), 'mid')
        self.assertEqual(noise.file_class(['always-timeout', 'flips'], 1.0, 1.25, 2), 'flips')
        self.assertEqual(noise.file_class([None], None, 1.25, 2), 'unknown')

    def test_night_seed_policy_and_baseline_first_plan(self):
        policy = night.parse_policy('flips=10', min_seeds=1)
        count = lambda entry, targeted=False: night.seed_count(entry, policy, targeted)
        self.assertEqual([count(dict(**{'class': c}, median_wall_s=60)) for c in
                          ('quiet', 'mid', 'noisy', 'flips', 'always-timeout', 'unknown')],
                         [2, 3, 8, 10, 1, 3])
        self.assertEqual(count({'class': 'always-timeout'}, targeted=True), 3)
        self.assertEqual(count({'class': 'quiet', 'median_wall_s': 1}), 3)
        self.assertEqual(count({}), 3)
        self.assertEqual(night.seed_count({}, night.parse_policy('', 10)), 10)
        self.assertEqual(night.seed_count({'class': 'always-timeout'}, night.parse_policy('', 10)), 1)
        self.assertEqual(night.seed_count({'class': 'always-timeout'}, night.parse_policy('', 10),
                                          targeted=True), 10)
        with self.assertRaises(ValueError):
            night.parse_policy('loud=3')
        rows = [dict(path='A/a.vpr'), dict(path='A/b.vpr')]
        tasks = night.plan([rows], ['W', 'M'], lambda r: [0, 1] if r['path'] == 'A/a.vpr' else [0],
                           3, baseline='M')
        for path, n in (('A/a.vpr', 2), ('A/b.vpr', 1)):
            mine = [(arm, seed) for row, arm, seed in tasks if row['path'] == path]
            self.assertEqual(sorted(mine), sorted((a, s) for a in 'MW' for s in range(n)))
            self.assertEqual({arm for arm, _ in mine[:n]}, {'M'})

    def test_night_cap_and_paired_interval(self):
        r = lambda arm, wall, outcome='verified': dict(arm=arm, outcome=outcome, wall_s=wall)
        records = {('M', 0): r('M', 20), ('M', 1): r('M', 5)}
        self.assertEqual(night.cap(records, 'M', 4, 30, 300), 80)
        self.assertEqual(night.cap({('M', 0): r('M', 2)}, 'M', 4, 30, 300), 30)
        self.assertEqual(night.cap({('M', 0): r('M', 200)}, 'M', 4, 30, 300), 300)
        self.assertEqual(night.cap({('M', 0): r('M', 9, 'timeout')}, 'M', 4, 30, 300), 300)
        self.assertEqual(night.cap({}, 'M', 4, 30, 300), 300)
        close = {('M', 0): r('M', 10), ('M', 1): r('M', 10), ('W', 0): r('W', 9), ('W', 1): r('W', 9.1)}
        far = {**close, ('W', 1): r('W', 20)}
        self.assertFalse(night.wide(close, ['M', 'W'], 'M', [0, 1], 0.2))
        self.assertTrue(night.wide(far, ['M', 'W'], 'M', [0, 1], 0.2))
        self.assertFalse(night.wide({**far, ('W', 1): r('W', 20, 'capped')},
                                    ['M', 'W'], 'M', [0, 1], 0.2))

    def test_night_schedule_waits_for_baseline_and_extends_wide_files(self):
        row = dict(path='A/a.vpr')
        tasks = night.plan([[row]], ['M', 'W'], [0, 1], 0, baseline='M')
        schedule = night.Schedule(tasks, [], ['M', 'W'], 'M', (0.2, 3), night.random.Random(0))
        first, _ = schedule.get(0)
        second, _ = schedule.get(0)
        self.assertEqual({first[1], second[1]}, {'M'})
        self.assertEqual(schedule.get(0), (None, False))  # W waits for the baseline
        walls = dict(M=10, W=10)
        for task in (first, second):
            schedule.complete(task, dict(path=row['path'], arm='M', seed=task[2],
                                         outcome='verified', wall_s=walls['M']))
        self.assertEqual(schedule.timeout(row, 'W', 4, 30, 300), 40)
        for seed in (0, 1):
            task, _ = schedule.get(0)
            self.assertEqual(task[1], 'W')
            schedule.complete(task, dict(path=row['path'], arm='W', seed=task[2],
                                         outcome='verified', wall_s=[9, 20][task[2]]))
        # Wide: seed 2 for both arms, baseline first; then the seed limit stops it.
        task, _ = schedule.get(0)
        self.assertEqual(task[1:], ('M', 2))
        self.assertEqual(len(schedule), 1)
        schedule.complete(task, dict(path=row['path'], arm='M', seed=2, outcome='verified', wall_s=10))
        task, _ = schedule.get(0)
        schedule.complete(task, dict(path=row['path'], arm='W', seed=2, outcome='verified', wall_s=20))
        self.assertEqual(schedule.get(0), (None, True))
        # Resume: completed records, extension seeds included, plan nothing again.
        done = [dict(path=row['path'], arm=a, seed=s, outcome='verified', wall_s=10)
                for a in 'MW' for s in (0, 1)]
        self.assertEqual(len(night.Schedule(tasks, done, ['M', 'W'], 'M')), 0)
        done = [r for r in schedule.records[row['path']].values()]
        resumed = night.Schedule(tasks, done, ['M', 'W'], 'M', (0.2, 3), night.random.Random(0))
        self.assertEqual((len(resumed), resumed.seeds[row['path']]), (0, {0, 1, 2}))

    def test_night_caps_arm_timeouts_and_records_capped(self):
        rows = run.read_jsonl(self.manifest()[0])
        args, arms = self.night_args(rows)
        timeouts = []

        def measure(row, task, lane):
            timeouts.append((lane.label.split('-')[0], task.timeout))
            if lane.label.startswith('W'):
                return dict(outcome='timeout', wall_s=task.timeout + 1, seed=task.seed)
            return dict(outcome='verified', wall_s=[3, 12][task.seed], seed=task.seed)

        with patch.object(run, 'build_runner', return_value=args.java), \
                patch.object(run, 'run_warm_once', side_effect=measure):
            night.execute(args, arms, [rows])
        self.assertEqual(sorted(timeouts), [('M', 300), ('M', 300), ('W', 48), ('W', 48)])
        records = run.read_jsonl(args.output_directory / 'results.jsonl')
        self.assertEqual({(r['arm'], r['outcome'], r['timeout_s']) for r in records},
                         {('M', 'verified', 300), ('W', 'capped', 48)})
        report = analyze.analyze([args.output_directory], 'M', ['wall_s'], 2.0, 50, [])
        c = report['subsets']['all']['comparisons']['W']['wall_s']
        self.assertEqual((c['n'], [x['at_least'] for x in c['capped']]), (0, [48 / 7.5]))
        # One capped seed of three bounds the median, not the cap ratio.
        b = lambda arm, seed, wall, outcome='verified': dict(path='p', arm=arm, seed=seed,
                                                            outcome=outcome, wall_s=wall, timeout_s=40)
        files = {'p': {'M': {s: b('M', s, 10) for s in range(3)},
                       'W': {0: b('W', 0, 41, 'capped'), 1: b('W', 1, 11), 2: b('W', 2, 12)}},
                 'q': {'M': {0: b('M', 0, 1), 1: b('M', 1, 1)}, 'W': {0: b('W', 0, 1)}}}
        c = analyze.compare(files, 'M', 'W', 'wall_s', 2.0, 50, analyze.random.Random(0))
        self.assertEqual(([x['at_least'] for x in c['capped']], c['excluded']), ([1.2], 1))

    def test_night_profile_seeds_are_snapshotted_and_shared_by_arms(self):
        rows = run.read_jsonl(self.manifest()[0])
        profile = self.root / 'profile.json'
        profile.write_text(json.dumps(dict(files={rows[0]['path']: {'class': 'noisy',
                                                                    'median_wall_s': 50}})))
        args, arms = self.night_args(rows, seeds=None, profile=profile, baseline=None,
                                     seed_policy=night.parse_policy(''))
        seen = []

        def measure(row, task, lane):
            seen.append((lane.label[0], task.seed))
            return dict(outcome='verified', wall_s=1, seed=task.seed)

        with patch.object(run, 'build_runner', return_value=args.java), \
                patch.object(run, 'run_warm_once', side_effect=measure):
            night.execute(args, arms, [rows])
            profile.write_text(json.dumps(dict(files={})))
            night.execute(args, arms, [rows])  # resumes from the snapshot: nothing to run
        self.assertEqual(sorted(seen), [(a, s) for a in 'MW' for s in range(8)])
        self.assertIn('noisy', (args.output_directory / 'profile.json').read_text())

    def test_warm_defaults_respect_explicit_settings(self):
        self.assertEqual(run.warm_defaults(['-Xmx4g'], []),
                         (['-Xmx4g', '-Xss512m', '-XX:G1PeriodicGCInterval=10000'],
                          ['--numberOfParallelVerifiers', '1']))
        self.assertEqual(run.warm_defaults(['-Xss64m', '-XX:G1PeriodicGCInterval=0'],
                                           ['--numberOfParallelVerifiers=4']),
                         (['-Xss64m', '-XX:G1PeriodicGCInterval=0'], ['--numberOfParallelVerifiers=4']))

    def test_tiers_tags_select_t1_and_cost(self):
        text = """// xs ++ ys in a comment is ignored
            method m(xs: Seq[Int]) requires forall i: Int :: i in [0..3) ==> acc(a[i].f)
            { var y: Seq[Int] := xs[1..]; assert xs[0] == y[..2][0]; package true --* true }"""
        self.assertEqual(tiers.scan(text), ['qp', 'seq', 'seq_index', 'seq_slice', 'wand'])
        self.assertIn('prusti_lifetime', tiers.scan('inhale acc(DeadLifetimeToken(l))'))
        self.assertIn('qp_predicate', tiers.scan('inhale forall x: Ref :: x in s ==> acc(P(x))'))
        self.assertIn('qp', tiers.scan('inhale forall x: Ref :: {x.f} {g(x)} x in s ==> acc(x.f)'))
        self.assertNotIn('seq_index', tiers.scan('var x: Map[Int, Int]'))
        rows = [dict(path=f'A/{i}.vpr') for i in range(10)]
        tags = {'A/0.vpr': ['wand'], 'A/1.vpr': ['seq']}
        chosen = tiers.select(rows, tags, {'wand'}, 3, 1)
        self.assertEqual(len(chosen), 4)
        self.assertIn(rows[0], chosen)
        self.assertEqual(chosen, tiers.select(list(reversed(rows)), tags, {'wand'}, 3, 1))
        profile = dict(files={'A/0.vpr': {'class': 'quiet', 'median_wall_s': 8},
                              'A/1.vpr': {'class': 'flips', 'median_wall_s': 1},
                              'A/2.vpr': {'class': 'always-timeout', 'median_wall_s': None},
                              'A/3.vpr': {'class': 'quiet', 'median_wall_s': 1}})
        t1 = [r['path'] for r in tiers.t1(rows, profile, 5, 2, 0)]
        self.assertEqual(len(t1), 5)
        self.assertTrue({'A/0.vpr', 'A/1.vpr', 'A/2.vpr'} <= set(t1))
        hours, runs, missing = tiers.cost(rows[:4], profile, night.parse_policy(''), 30, 300)
        self.assertEqual((runs, missing), (3 + 3 + 1 + 3, 0))
        self.assertAlmostEqual(hours * 3600, 3 * 8 + 3 * 1 + 300 + 3 * 1)

    def test_tiers_t0_copies_imports_and_records_arguments(self):
        self.source('F/_preambles/p.vpr', 'domain D {}')
        self.source('F/x/a.vpr', 'import "../_preambles/p.vpr"\nmethod a() {}')
        extra = self.root / 'test.vpr'
        extra.write_text('method t() {}')
        spec = tiers.parse_spec(f'# cases\nF/x/a.vpr\nSil/t.vpr {extra} -- --maskHeapMode\n')
        root = self.root / 't0'
        rows = tiers.build_t0(spec, self.corpus, root)
        self.assertTrue((root / 'F/_preambles/p.vpr').exists())
        self.assertEqual([(r['path'], r['group'], r.get('verifier_args')) for r in rows],
                         [('F/x/a.vpr', 'F', None), ('Sil/t.vpr', 'Sil', ['--maskHeapMode'])])
        manifest = self.root / 't0.jsonl'
        curate.write_manifest(manifest, rows)
        self.assertEqual(run.load_manifest(manifest, root), rows)


if __name__ == '__main__':
    unittest.main()
