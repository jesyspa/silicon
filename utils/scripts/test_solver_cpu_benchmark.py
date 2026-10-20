import contextlib
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
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
        a = self.args('--solver-memory-max=25769803776', '--solver-swap-max=0')
        self.assertEqual(a.solver_memory_max, 25769803776)
        self.assertEqual(a.solver_swap_max, 0)
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            self.args('--solver-memory-max=-1')

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
            record['cpu_stats'] = {'usage_usec': 10000 if cgroup.name.startswith('verifier-')
                                   else 30000, 'user_usec': 0, 'system_usec': 0}

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
        self.assertEqual(record['total_process_cpu_s'], 0.04)
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

    @unittest.skipUnless(os.environ.get('VIPER_TEST_CGROUP_ROOT'), 'needs delegated cgroup root')
    def test_real_cgroup_cpu_and_timeout(self):
        a = self.args()
        a.cgroup_root = Path(os.environ['VIPER_TEST_CGROUP_ROOT'])
        memory_enabled = 'memory' in (a.cgroup_root / 'cgroup.subtree_control').read_text()
        if memory_enabled:
            a.solver_memory_max = 128 * 1024 * 1024
            a.solver_swap_max = 0
            (self.root / 'inputs').mkdir()
            a.output_directory = self.root / 'inputs' / 'attempt'
            (self.root / 'solver-limits.json').write_text(json.dumps(dict(
                solver_memory_max=256 * 1024 * 1024, solver_swap_max=0)))
        a.java = self.root / 'java'
        a.z3 = self.root / 'z3'
        a.java.write_text(f'#!{sys.executable}\n' + '''import pathlib, subprocess, sys, time
if '-version' in sys.argv or '--help' in sys.argv:
    sys.exit(0)
print(pathlib.Path('/proc/self/cgroup').read_text(), flush=True)
wrapper = sys.argv[sys.argv.index('--z3Exe') + 1]
subprocess.run([wrapper], check=True)
end = time.process_time() + .08
while time.process_time() < end:
    pass
''')
        a.java.chmod(0o700)
        solver = f'#!{sys.executable}\n' + '''import pathlib, sys, time
if '-version' in sys.argv:
    sys.exit(0)
leaf = pathlib.Path('/sys/fs/cgroup' + pathlib.Path('/proc/self/cgroup').read_text().strip().split('::')[1])
print(pathlib.Path('/proc/self/cgroup').read_text(), flush=True)
if (leaf / 'memory.max').exists():
    print('memory.max=' + (leaf / 'memory.max').read_text().strip(), flush=True)
    print('memory.swap.max=' + (leaf / 'memory.swap.max').read_text().strip(), flush=True)
end = time.process_time() + .08
while time.process_time() < end:
    pass
'''
        a.z3.write_text(solver)
        a.z3.chmod(0o700)
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(bench.benchmark(a), 0)
        record = json.loads((a.output_directory / 'measurement.json').read_text())
        self.assertGreater(record['solver_cpu_s']['usage'], .07)
        self.assertGreater(record['total_process_cpu_s'], record['solver_cpu_s']['usage'] + .07)
        self.assertIn('verifier-cpu-', (a.output_directory / 'stdout.log').read_text())
        self.assertIn('solver-cpu-', (a.output_directory / 'stdout.log').read_text())
        if memory_enabled:
            log = (a.output_directory / 'stdout.log').read_text()
            self.assertIn('memory.max=268435456', log)
            self.assertIn('memory.swap.max=0', log)
            self.assertEqual(record['memory_events']['oom_kill'], 0)
        a.output_directory = self.root / 'timeout'
        a.timeout = 1
        a.z3.write_text(solver + '\ntime.sleep(10)\n')
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(bench.benchmark(a), 1)
        record = json.loads((a.output_directory / 'measurement.json').read_text())
        self.assertTrue(record['timed_out'])
        self.assertGreater(record['total_process_cpu_s'], record['solver_cpu_s']['usage'])
        self.assertFalse(Path(record['cgroup']).exists())
        self.assertEqual(record['errors'], [])

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
