import tempfile
import unittest
from pathlib import Path

import suite_times

REPORT = """<testsuite>
<testcase classname="a.SiliconTests" name="all/linked-list-qp-append.vpr" time="300.0"/>
<testcase classname="a.SiliconTests" name="all/other.vpr" time="2.5"/>
<testcase classname="a.SiliconTests" name="all/broken.vpr" time="1.0"><failure/></testcase>
</testsuite>"""


class Tests(unittest.TestCase):
    def test_excluded_test_keeps_its_outcome_but_not_its_time(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / 'r.xml').write_text(REPORT)
            outcomes, seconds, n = suite_times.totals(suite_times.load(d))
        self.assertEqual(outcomes, {'passed': 2, 'failed': 1})
        self.assertEqual((seconds, n), (3.5, 2))


if __name__ == '__main__':
    unittest.main()
