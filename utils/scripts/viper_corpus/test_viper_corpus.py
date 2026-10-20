import argparse
import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import compare
import curate
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
        self.assertEqual(run.outcome(dict(base, returncode=1, success=False),
                                     'Verification failed with 2 errors'), 'failed')
        self.assertEqual(run.outcome(dict(base, returncode=1), '2 errors found'), 'failed')
        self.assertEqual(run.outcome(dict(base, returncode=1),
                                     'The following errors were found:'), 'failed')
        self.assertEqual(run.outcome(dict(base, returncode=1),
                                     'The following errors were found: An exception occurred'), 'crash')
        self.assertEqual(run.outcome(dict(base, returncode=1), 'Verification timed out'), 'timeout')
        self.assertEqual(run.outcome(dict(base, returncode=1),
                                     'Verification aborted due to a timeout after 1 second'), 'timeout')
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
                                  jvm_arg=[], verifier_arg=[], jobs=2), rows

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

        def invoke(command, **kwargs):
            self.assertEqual(Path(command[1]), Path(run.bench.__file__).resolve())
            self.assertEqual(command[-1], str(self.corpus / rows[0]['path']))
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
        self.assertIsNone(compare.ratio(row('x', 1, 1, 'timeout'),
                                        row('x', 2, 2, 'timeout'), 'solver_cpu_s'))

    def test_compare_empty_and_zero_pairs(self):
        self.assertEqual(compare.compare({}, {})['groups'], {})
        row = dict(path='x', group='A', sha256='h', outcome='crash')
        metrics = compare.compare({'x': row}, {'x': row})['groups']['A']['metrics']
        self.assertIsNone(metrics['wall_s']['geometric_mean_ratio'])


if __name__ == '__main__':
    unittest.main()
