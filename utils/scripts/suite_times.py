#!/usr/bin/env python3
"""Per-test times and outcomes from JUnit reports of `sbt test`.

python3 suite_times.py RUN_DIR... (directories of *.xml, e.g. target/test-reports or test-<arm>-<n>-reports)

Tests in EXCLUDED_FROM_TIMING stay in the outcome counts but not in the timing totals. Their time
depends on Z3's search, not on the change under test: linked-list-qp-append takes 13 to 600 s
(its Z3 query is a matching loop that can pass 8 GB).
"""
import collections
import glob
import sys
import xml.etree.ElementTree as ET

EXCLUDED_FROM_TIMING = ('linked-list-qp-append',)


def excluded(name):
    return any(e in name for e in EXCLUDED_FROM_TIMING)


def load(directory):
    """{(class, test): (seconds, outcome)}; outcome is passed, failed or skipped."""
    tests = {}
    for path in glob.glob(directory + '/*.xml'):
        for case in ET.parse(path).getroot().iter('testcase'):
            outcome = ('failed' if case.find('failure') is not None or case.find('error') is not None
                       else 'skipped' if case.find('skipped') is not None else 'passed')
            tests[(case.get('classname', '').split('.')[-1], case.get('name'))] = (float(case.get('time') or 0), outcome)
    return tests


def totals(tests):
    """Outcome counts over all tests, and time over the tests not excluded from timing."""
    outcomes = collections.Counter(o for _, o in tests.values())
    timed = {k: t for k, (t, _) in tests.items() if not excluded(k[1])}
    return dict(outcomes), sum(timed.values()), len(timed)


def main(directories):
    runs = {d.rstrip('/').split('/')[-1].replace('-reports', ''): load(d) for d in directories}
    for name, tests in runs.items():
        outcomes, seconds, n = totals(tests)
        skipped_time = sum(t for k, (t, _) in tests.items() if excluded(k[1]))
        print(f'{name}: outcomes {outcomes}; summed time {seconds:.1f} s over {n} tests; '
              f'excluded from timing: {skipped_time:.1f} s')


if __name__ == '__main__':
    main(sys.argv[1:])
