import contextlib
import io
import json
from pathlib import Path
import signal
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import solver_cpu_benchmark as bench


class BenchmarkTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'cgroup.controllers').touch()
        self.file = self.root / 'executable'
        self.file.write_text('#!/bin/sh\nexit 0\n')
        self.file.chmod(0o700)
        self.argv = []
        for name in ('java', 'jar', 'z3'):
            self.argv += ['--' + name, str(self.file)]
        self.argv += ['--cgroup-root', str(self.root), '--output-directory',
                      str(self.root / 'output'), str(self.file)]

    def args(self, *extra):
        return bench.arguments(self.argv + list(extra))

    def test_argument_guards(self):
        for token in ('--z3Exe=x', '--z3=x', '--prover', '--timeout=2',
                      '--tempDirectory=x', '--proverLogFile=x', '--', '@args', '-t'):
            with self.subTest(token=token), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    self.args('--verifier-arg=' + token)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.args('--jvm-arg=-jar')
        a = self.args('--verifier-arg=--proverArgs=-smt2', '--jvm-arg=-Xmx1g')
        self.assertEqual(a.jvm_arg, ['-Xmx1g'])

    def test_output_never_overwritten(self):
        a = self.args()
        a.output_directory.mkdir()
        marker = a.output_directory / 'measurement.json'
        marker.write_text('untouched')
        with self.assertRaises(FileExistsError):
            bench.benchmark(a)
        self.assertEqual(marker.read_text(), 'untouched')

    def test_counters_cleanup_and_leftovers(self):
        (self.root / 'cpu.stat').write_text('usage_usec 30\nuser_usec 20\nsystem_usec 10\n')
        (self.root / 'cgroup.procs').write_text('123\n456\n')
        (self.root / 'cgroup.events').write_text('populated 1\n')
        record = dict(errors=[], leftover_pids=[], leftover_failure=False)
        with patch.object(Path, 'rmdir') as remove, patch.object(Path, 'write_text') as write:
            with patch.object(Path, 'read_text', side_effect=[
                    '123\n456\n', 'populated 1\n', 'populated 0\n',
                    'usage_usec 30\nuser_usec 20\nsystem_usec 10\n']):
                bench.cleanup(self.root, record)
        self.assertEqual(record['leftover_pids'], ['123', '456'])
        self.assertTrue(record['leftover_failure'])
        self.assertEqual(record['solver_cpu_s']['system'], 0.00001)
        write.assert_called_once_with('1\n')
        remove.assert_called_once()
        self.assertEqual(record['errors'], [])

    def test_cleanup_errors_are_retained(self):
        record = dict(errors=[], leftover_pids=[])
        bench.cleanup(self.root / 'absent', record)
        self.assertEqual(len(record['errors']), 3)

    def test_timeout_records_and_cleans(self):
        a = self.args()
        proc = unittest.mock.Mock(pid=123, returncode=-signal.SIGKILL)
        proc.wait.side_effect = [subprocess.TimeoutExpired('java', 1), None]

        def cleaned(cgroup, record):
            cgroup.rmdir()
            record['cpu_stats'] = {'usage_usec': 0, 'user_usec': 0, 'system_usec': 0}

        with patch.object(bench, 'version', return_value={}), \
                patch.object(bench, 'cpu_stats', return_value={}), \
                patch.object(bench.os, 'access', return_value=True), \
                patch.object(bench.subprocess, 'Popen', return_value=proc), \
                patch.object(bench.os, 'killpg') as kill, \
                patch.object(bench.Path, 'read_text', return_value=''), \
                patch.object(bench, 'cleanup', side_effect=cleaned), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(bench.benchmark(a), 1)
        record = json.loads((a.output_directory / 'measurement.json').read_text())
        self.assertTrue(record['timed_out'])
        self.assertEqual(record['returncode'], -signal.SIGKILL)
        self.assertFalse(Path(record['cgroup']).exists())
        kill.assert_called_once_with(123, signal.SIGKILL)

    def test_inherited_secrets_not_recorded(self):
        a = self.args()
        stdout = io.StringIO()
        with patch.dict(bench.os.environ, {'UNRELATED_SECRET': 'private-token-value'}), \
                patch.object(bench, 'version', return_value={}), \
                contextlib.redirect_stdout(stdout):
            bench.benchmark(a)
        record = (a.output_directory / 'measurement.json').read_text()
        self.assertNotIn('environment', json.loads(record))
        for text in (record, stdout.getvalue()):
            self.assertNotIn('UNRELATED_SECRET', text)
            self.assertNotIn('private-token-value', text)

    def test_wrapper_exec_preserves_pid(self):
        cgroup = self.root / 'leaf'
        cgroup.mkdir()
        self.file.write_text('#!/bin/sh\nprintf "%s" "$$"\n')
        wrapper = Path(bench.__file__).with_name('z3-cpu-wrapper.sh')
        proc = subprocess.Popen([str(wrapper)], stdout=subprocess.PIPE,
                                env={'SOLVER_CGROUP': str(cgroup), 'SOLVER_Z3': str(self.file)})
        output, _ = proc.communicate(timeout=5)
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(int(output), proc.pid)
        self.assertEqual(int((cgroup / 'cgroup.procs').read_text()), proc.pid)


if __name__ == '__main__':
    unittest.main()
