import unittest

from compare_rmsnorm_runs import compare
from test_summarize_rmsnorm_nvfp4 import complete_log, tile_log


class CompareRmsnormTest(unittest.TestCase):
    def test_rejects_speedup_between_different_tile_shapes(self):
        previous = complete_log(samples=1, cases=1, single=True)
        with self.assertRaisesRegex(ValueError, 'different numerical configuration'):
            compare(previous, tile_log())
        row, = compare(tile_log(), tile_log())
        self.assertEqual(row['speedup'], 1)

    def test_matches_common_cases(self):
        for rms_cases, full_cases in ((8, 11), (17, 23)):
            with self.subTest(rms_cases=rms_cases, full_cases=full_cases):
                rows = compare(complete_log(cases=rms_cases), complete_log(cases=full_cases))
                self.assertEqual(len(rows), rms_cases)
                self.assertTrue(all(row["speedup"] == 1 and row["cycle_change_percent"] == 0 for row in rows))

    def test_rejects_changed_inputs(self):
        with self.assertRaises(ValueError):
            compare(complete_log(), complete_log().replace("input=fp32", "input=bf16_rounded_fp32"))

    def test_matches_single_run_common_cases(self):
        rows = compare(complete_log(samples=1, cases=8, single=True),
                       complete_log(samples=1, cases=11, single=True))
        self.assertEqual(len(rows), 8)
        self.assertTrue(all(row["speedup"] == 1 and row["cycle_change_percent"] == 0 for row in rows))


if __name__ == "__main__":
    unittest.main()
