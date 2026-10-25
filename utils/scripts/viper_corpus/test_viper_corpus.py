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
from unittest.mock import patch

import analyze
import compare
import curate
import night
import run


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
        args.warm_runs = 2
        lanes = run.queue.Queue()
        lanes.put(run.Lane(args, 1, java))
        self.addCleanup(lanes.queue[0].stop)
        record = run.run_warm(rows[0], args, lanes)
        self.assertEqual(record['runner_served'], 1)
        self.assertEqual([w['runner_served'] for w in record['warmup']], [0])

    def test_prover_events_parse_retries_and_skipped_checks(self):
        log = ('Prover 00 retried an assertion in a fresh prover: first attempt 2003 ms, '
               'restart and replay 412 ms, retry 95 ms, answer unsat\n'
               'Decider 00 skipped 7 contradicted checks\n'
               'Prover 01 retried an assertion in a fresh prover: first attempt 1990 ms, '
               'restart and replay 30 ms, retry 0 ms, answer out of memory\n'
               'Decider 01 skipped 2 contradicted checks\n')
        events = run.prover_events(log)
        self.assertEqual(events['skipped_checks'], 9)
        self.assertEqual(events['retries'], [
            dict(prover='00', first_ms=2003, restart_ms=412, retry_ms=95, answer='unsat'),
            dict(prover='01', first_ms=1990, restart_ms=30, retry_ms=0, answer='out of memory')])
        self.assertEqual(run.prover_events('Silicon found 1 error'),
                         dict(retries=[], skipped_checks=0))

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
            # One warm-up per started runner, that is per lane and arm used.
            started = {(r['lane'], r['arm']) for r in results}
            self.assertEqual(len(list((out / 'warmup').glob('*.log'))), len(started))
            self.assertEqual(len(run.read_jsonl(out / 'warmup' / 'wall.jsonl')), len(started))
            night.main(argv)
            self.assertEqual(len(run.read_jsonl(out / 'results.jsonl')), len(results))
            with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
                night.main([*argv, '--timeout', '9'])

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
        rows[1].update(skipped_checks=4, retries=[
            dict(prover='00', first_ms=2000, restart_ms=500, retry_ms=100, answer='unsat')])
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
        r = report['retries']
        self.assertEqual(r['table']['W'][0], dict(
            tasks=3, tasks_with_retry=1, retries=1, first_s=2.0, restart_s=0.5, retry_s=0.1,
            answers={'unsat': 1}, skipped_checks=4))
        self.assertEqual([(t['path'], t['arm'], t['seed']) for t in r['tasks']], [('stable', 'W', 0)])
        self.assertIn('2000/500/100 unsat', analyze.markdown(report))
        only = analyze.analyze([out], 'M', ['wall_s'], 2.0, 200, [], only={'flips'})
        self.assertEqual(only['files'], 1)


if __name__ == '__main__':
    unittest.main()
