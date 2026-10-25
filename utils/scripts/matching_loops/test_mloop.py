import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

import mloop
import smtq

HERE = Path(__file__).resolve().parent


def find_z3():
    for candidate in [os.environ.get('MLOOP_Z3'), shutil.which('z3'),
                      os.path.expanduser('~/opt/z3-4.16.0-x64-glibc-2.39/bin/z3')]:
        if candidate and os.access(candidate, os.X_OK):
            return candidate
    return None


Z3 = find_z3()

PREAMBLE = """(set-option :print-success true)
(set-option :smt.mbqi false)
(set-option :auto_config false)
(set-option :smt.qi.eager_threshold 100)
(declare-fun f (Int) Int)
(declare-fun g (Int) Bool)
"""
LOOP = '(assert (forall ((x Int)) (! (> (f x) (f (+ x 1))) :pattern ((f x)) :qid |shift|)))\n'
TAME = '(assert (forall ((x Int)) (! (=> (g x) (> (f x) 0)) :pattern ((g x)) :qid |tame|)))\n'


class Tests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def tap(self, body, answers, closed=None):
        """A tap directory for one process whose check-sats get the given (delay, answer)."""
        lines, out, now = [], [], 100.0
        replies = iter(answers)
        for line in body.splitlines(keepends=True):
            if line.startswith('(check-sat'):
                lines.append(f';@T {now:.6f}\n')
                reply = next(replies, None)
                if reply:
                    now += reply[0]
                    out.append(f'{now:.6f} {reply[1]}\n')
            lines.append(line)
        if closed is not None:
            lines.append(f';@E {closed:.6f}\n')
        self.write('tap/7.in', ''.join(lines))
        self.write('tap/7.out', ''.join(out))
        self.write('tap/7.args', json.dumps(['-smt2', '-in', 'memory_max_size=3000', 'smt.random_seed=0']))
        return self.root / 'tap'

    def test_queries_time_each_check_sat_and_the_one_in_flight(self):
        body = PREAMBLE + '(set-option :timeout 500)\n(check-sat)\n(push 1)\n(check-sat)\n(check-sat)\n'
        tap = self.tap(body, [(0.5, 'unsat'), (2.0, 'unknown')], closed=110.0)
        records = mloop.queries(tap)
        self.assertEqual([r['result'] for r in records], ['unsat', 'unknown', 'none'])
        self.assertAlmostEqual(records[0]['seconds'], 0.5)
        self.assertAlmostEqual(records[1]['seconds'], 2.0)
        self.assertAlmostEqual(records[2]['seconds'], 7.5)
        self.assertEqual(records[0]['timeout_ms'], 500)

    def test_cut_without_history_keeps_only_the_final_check_sat(self):
        body = (PREAMBLE + '(set-option :timeout 10)\n(check-sat)\n(get-info :reason-unknown)\n'
                + LOOP + '(check-sat)\n(assert (g 1))\n')
        tap = self.tap(body, [(0.1, 'unknown'), (3.0, 'unknown')])
        line = mloop.queries(tap)[1]['line']
        target = self.root / 'prefix.smt2'
        mloop.cut(tap / '7.in', line, target, history=False, timeout_ms=4000)
        text = target.read_text()
        self.assertEqual(text.count('(check-sat'), 1)
        self.assertNotIn(';@', text)
        self.assertNotIn('eager_threshold', text)
        self.assertNotIn('(set-option :timeout 10)', text)
        self.assertNotIn('(assert (g 1))', text)
        self.assertTrue(text.endswith('(set-option :timeout 4000)\n(check-sat)\n(get-info :reason-unknown)\n'
                                      '(get-info :all-statistics)\n(exit)\n'))

    def test_cut_with_history_caps_earlier_queries_and_writes_a_baseline(self):
        body = PREAMBLE + '(check-sat)\n' + LOOP + '(check-sat)\n'
        tap = self.tap(body, [(30.0, 'unknown'), (3.0, 'unknown')])
        records = mloop.queries(tap)
        caps = mloop.history_caps(records, '7')
        self.assertEqual(caps, {records[0]['line']: 10000})
        full, pre = self.root / 'full.smt2', self.root / 'pre.smt2'
        mloop.cut(tap / '7.in', records[1]['line'], full, True, 4000, caps=caps)
        mloop.cut(tap / '7.in', records[1]['line'], pre, True, 4000, final=False, caps=caps)
        self.assertIn('(set-option :timeout 10000)\n(check-sat)\n', full.read_text())
        self.assertEqual(full.read_text().count('(check-sat'), 2)
        self.assertEqual(pre.read_text().count('(check-sat'), 1)
        self.assertTrue(pre.read_text().endswith('(get-info :all-statistics)\n(exit)\n'))

    def test_profile_sums_copies_of_a_qid(self):
        err = self.write('err', '[quantifier_instances]  shift :     40 :   0 :   0 :  20 : 21\n'
                                '[quantifier_instances]  shift :      2 :   0 :   0 :  90 : 91\n'
                                '[quantifier_instances] old : 5 : 3 : 4\n')
        profile = mloop.parse_profile(err)
        self.assertEqual(profile['shift'], [42, 90, 91.0])
        self.assertEqual(profile['old'], [5, 3, 4.0])

    def test_output_statistics_are_relative_to_the_baseline_block(self):
        text = ('success\n(:quant-instantiations 100\n :max-generation 5)\nunknown\n'
                '(:reason-unknown "(incomplete quantifiers)")\n'
                '(:quant-instantiations 350\n :max-generation 40\n :time 1.5)\n')
        parsed = mloop.parse_output(text)
        self.assertEqual(parsed['result'], 'unknown')
        self.assertEqual(parsed['reason_unknown'], '(incomplete quantifiers)')
        self.assertEqual(parsed['statistics']['quant-instantiations'], 250)
        self.assertEqual(parsed['statistics']['max-generation'], 40)

    def test_feeding_terms_find_self_loops_per_pattern(self):
        path = self.write('q.smt2', PREAMBLE + LOOP + TAME
                          + '(assert (forall ((a Int) (b Int) (c Int)) (! (=> (and (g a) (g b)) (g c)) '
                            ':pattern ((g a) (g b)) :pattern ((g c) (g b)) :qid |trans|)))\n')
        quants = smtq.quantifiers(path)
        self.assertEqual(smtq.feeding_terms(quants['shift'], quants['shift']), ['(f (+ x 1))'])
        self.assertEqual(smtq.feeding_terms(quants['tame'], quants['tame']), [])
        self.assertEqual(smtq.feeding_terms(quants['trans'], quants['trans']), ['(g a)', '(g c)'])
        self.assertEqual(smtq.trigger_cycles([quants['shift'], quants['tame']]), [['shift']])

    def test_unnamed_nested_quantifiers_use_z3_line_names(self):
        path = self.write('q.smt2', PREAMBLE + '(assert (forall ((y Int)) (! (=> (g y) (forall ((x Int)) '
                                               '(! (> (f x) (f y)) :pattern ((f x))))) :pattern ((g y)) '
                                               ':qid |outer|)))\n')
        quants = smtq.quantifiers(path)
        self.assertEqual(quants['k!7'].parent, 'outer')
        self.assertIn('nested in outer', smtq.describe('k!7', quants))

    def test_describe_names_viper_sources_through_imports(self):
        self.write('lib/defs.vpr', 'domain D {\n  function h(x: Int): Bool\n\n  axiom h_trans {\n  true }\n}\n')
        main = self.write('main.vpr', 'import "lib/defs.vpr"\nmethod m() {}\n')
        sources = smtq.load_sources(main)
        quants = smtq.quantifiers(self.write('q.smt2', PREAMBLE + LOOP))
        self.assertEqual(smtq.describe('prog.h_trans', quants, sources), 'axiom h_trans at defs.vpr:4')
        self.assertIn('Silicon built-in', smtq.describe('$Seq[Int]_prog.Seq_append_index3', quants, sources))
        self.assertEqual(smtq.describe('prog./x/y/main.vpr@12@3@12@40-aux', quants, sources),
                         'user quantifier at main.vpr:12:3')

    def test_verdict_needs_generation_tracking_the_threshold_and_growth(self):
        def run(total, generation, profile):
            return dict(statistics={'quant-instantiations': total, 'max-generation': generation},
                        profile=profile)
        quants = smtq.quantifiers(self.write('q.smt2', PREAMBLE + LOOP + TAME))
        base = run(5000, 100, {'shift': [4000, 100, 1], 'tame': [900, 1, 1]})
        sweep = [(10, run(500, 10, {'shift': [400, 10, 1], 'tame': [900, 1, 1]})),
                 (30, run(1500, 30, {'shift': [1200, 30, 1], 'tame': [900, 1, 1]}))]
        verdict, culprits, feeding = mloop.loop_verdict(base, sweep, 100, quants)
        self.assertEqual((verdict, culprits), ('loop', ['shift']))
        base['profile']['eater'] = [9000, 100, 1]
        sweep[0][1]['profile']['eater'] = [100, 10, 1]
        self.assertEqual(mloop.loop_verdict(base, sweep, 100, quants, {'shift'})[1], ['shift', 'eater'])
        for name, size in [('a', 5000), ('b', 5000), ('c', 5000)]:
            base['profile'][name] = [size, 100, 1]
        self.assertEqual(mloop.loop_verdict(base, sweep, 100, quants, {'shift', 'a', 'b', 'c'})[1],
                         ['c', 'b', 'a', 'eater'])
        self.assertEqual(feeding['shift'], ['(f (+ x 1))'])
        calm = run(900, 3, {'tame': [900, 3, 1]})
        self.assertEqual(mloop.loop_verdict(calm, [], 100, quants)[0], 'no loop')

    @unittest.skipUnless(Z3, 'needs z3')
    def test_tap_records_input_and_timed_answers(self):
        tap = self.root / 'tap'
        tap.mkdir()
        env = dict(os.environ, MLOOP_Z3=Z3, MLOOP_TAP_DIR=str(tap))
        script = PREAMBLE + '(assert (g 1))\n(check-sat)\n(exit)\n'
        output = subprocess.run([sys.executable, str(HERE / 'z3tap.py'), '-smt2', '-in'], input=script,
                                capture_output=True, text=True, env=env, timeout=60).stdout
        self.assertIn('sat', output.split())
        records = mloop.queries(tap)
        self.assertEqual([r['result'] for r in records], ['sat'])
        self.assertIn(';@E ', next(tap.glob('*.in')).read_text())

    @unittest.skipUnless(Z3, 'needs z3')
    def test_tap_records_out_of_memory_from_stderr_as_the_answer(self):
        tap = self.root / 'tap'
        tap.mkdir()
        env = dict(os.environ, MLOOP_Z3=Z3, MLOOP_TAP_DIR=str(tap))
        script = (PREAMBLE.replace('eager_threshold 100', 'eager_threshold 100000') + LOOP
                  + '(assert (= (f 0) 3))\n(check-sat)\n')
        output = subprocess.run([sys.executable, str(HERE / 'z3tap.py'), '-smt2', '-in', 'memory_max_size=30'],
                                input=script, capture_output=True, text=True, env=env, timeout=60).stdout
        self.assertIn('out of memory', output)
        self.assertEqual([r['result'] for r in mloop.queries(tap)], ['out of memory'])

    @unittest.skipUnless(Z3, 'needs z3')
    def test_end_to_end_finds_a_shift_loop(self):
        body = PREAMBLE + TAME + '(assert (g 2))\n(check-sat)\n' + LOOP + '(assert (= (f 0) 3))\n(check-sat)\n'
        self.tap(body, [(0.01, 'sat'), (2.0, 'unknown')])
        self.write('run.json', json.dumps(dict(code=0, wall_s=3, end=None, jar='none', seed=0)))
        vpr = self.write('x.vpr', 'method m() {}\n')
        cpu = str(sorted(os.sched_getaffinity(0))[0])
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            mloop.main([str(vpr), '--reuse', '--out', str(self.root), '--z3', Z3, '--cpus', cpu,
                        '--replay-timeout', '20', '--min-seconds', '0', '--top', '1', '--seeds', '1'])
        report = json.loads((self.root / 'report.json').read_text())
        query = report['queries'][0]
        self.assertEqual(query['query']['index'], 1)
        self.assertEqual(query['verdict'], 'loop')
        self.assertEqual([c['qid'] for c in query['culprits']], ['shift'])
        self.assertEqual(query['quantifiers'][0]['self_feeding'], ['(f (+ x 1))'])
        self.assertIn('## 7 query 1', (self.root / 'report.md').read_text())


if __name__ == '__main__':
    unittest.main()
