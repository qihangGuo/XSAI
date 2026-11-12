import unittest

from summarize_rmsnorm_nvfp4 import CASE_SETS, summarize


def complete_log(samples=3, compact=False, cases=11, single=False):
    expected = CASE_SETS[str(cases)]
    metadata = ("format=5 workload=rmsnorm_nvfp4_evu quantization=evu alpha_bits=3b124925" if cases == 11
                else "format=5 workload=rmsnorm_evu quantization=not_measured" if cases == 8
                else "format=3 workload=rmsnorm_evu quantization=not_measured" if cases == 17
                else "format=4 workload=rmsnorm_nvfp4_evu quantization=evu alpha_bits=3b124925" if cases == 23
                else "alpha_bits=3b124925")
    if single:
        metadata = metadata.replace("format=5", "format=6")
    lines = [f"[RN] config rows=4 cols=512 samples={samples} input=fp32 eps_bits=3727c5ac warmup={0 if single else 1} cases={cases} {metadata}"]
    for stage, schedule in sorted(expected):
        if single:
            cycles = 200 if schedule == "row_major" else 100
            lines.append(f"[RN] stage={stage} schedule={schedule} cycles={cycles} inst=50 errors=0")
            continue
        if compact:
            cycles = 200 if schedule == "row_major" else 100
            lines.append(f"[RN] stage={stage} schedule={schedule} n={samples} min={cycles} median={cycles + 1} max={cycles + 2} inst=50 errors=0")
            continue
        for sample in range(samples):
            cycles = 200 if schedule == "row_major" else 100
            lines.append(f"[RN] stage={stage} schedule={schedule} sample={sample} cycles={cycles + sample} instructions=50 errors=0")
    return "\n".join(lines + ["[RN] PASS", "HIT GOOD TRAP"])


class RmsnormSummaryTest(unittest.TestCase):
    def test_batched_output(self):
        for cases in (8, 11):
            previous = complete_log(samples=1, cases=cases, single=True)
            batched = previous.replace("format=6", "format=7 quant_batch=16")
            self.assertEqual(summarize(previous), summarize(batched))
            with self.assertRaises(ValueError):
                summarize(batched.replace("quant_batch=16", "quant_batch=1"))

    def test_single_run_output(self):
        for cases in (8, 11):
            with self.subTest(cases=cases):
                text = complete_log(samples=1, cases=cases, single=True)
                rows = summarize(text)
                self.assertEqual(len(rows), cases)
                self.assertEqual(rows, summarize(complete_log(samples=1, cases=cases)))
                self.assertTrue(all(row["samples"] == 1 and row["cycles_min"] == row["cycles_median"] ==
                                    row["cycles_max"] for row in rows))
                wave = next(row for row in rows if row["stage"] == "sumsq" and row["schedule"] == "wave4")
                self.assertEqual(wave["speedup_vs_row_major"], 2)

    def test_rejects_invalid_single_run_output(self):
        full = complete_log(samples=1, single=True)
        first_result = next(line for line in full.splitlines() if line.startswith("[RN] stage="))
        for text in (full.replace("samples=1", "samples=3"), full.replace("warmup=0", "warmup=1"),
                     full.replace("cycles=100", "cycles=0", 1), full.replace("inst=50", "inst=0", 1),
                     full.replace("errors=0", "errors=1", 1), full.replace(first_result, ""),
                     full + "\n" + first_result):
            with self.subTest(text=text), self.assertRaises(ValueError):
                summarize(text)

    def test_optional_repeated_run_output(self):
        full = complete_log(compact=True).replace("format=5", "format=6").replace("warmup=1", "warmup=0")
        self.assertEqual(summarize(full), summarize(complete_log(compact=True)))

    def test_full_evu_quantization(self):
        rows = summarize(complete_log(compact=True))
        self.assertEqual(len(rows), 11)
        self.assertTrue(all(row["quantization"] == "evu" for row in rows))
        self.assertEqual(sum(row["stage"] == "fused_nvfp4" for row in rows), 2)
        with self.assertRaises(ValueError):
            summarize(complete_log().replace("quantization=evu", "quantization=cpu"))

    def test_medians_and_schedule_speedup(self):
        rows = summarize(complete_log())
        self.assertEqual(len(rows), 11)
        wave = next(row for row in rows if row["stage"] == "sumsq" and row["schedule"] == "wave4")
        self.assertEqual(wave["cycles_median"], 101)
        self.assertEqual(wave["cycles_per_row"], 25.25)
        self.assertEqual(wave["speedup_vs_row_major"], 201 / 101)

    def test_current_run_contains_only_evu_stages_and_overhead(self):
        rows = summarize(complete_log(compact=True))
        stages = {row["stage"] for row in rows}
        self.assertEqual(stages, {"empty", "sumsq", "scale_div_eps", "rsqrt_evu", "rms_evu",
                                  "quant_evu", "fused_nvfp4"})
        self.assertEqual(sum(row["stage"] == "scale_div_eps" for row in rows), 1)
        self.assertEqual(sum(row["stage"] == "quant_evu" for row in rows), 1)
        self.assertTrue(all(row["workload"] == "rmsnorm_nvfp4_evu" and
                            row["quantization"] == "evu" for row in rows))

    def test_rms_only_subset(self):
        rows = summarize(complete_log(cases=8, compact=True))
        self.assertEqual(len(rows), 8)
        self.assertEqual({row["stage"] for row in rows},
                         {"empty", "sumsq", "scale_div_eps", "rsqrt_evu", "rms_evu"})
        self.assertTrue(all(row["workload"] == "rmsnorm_evu" and
                            row["quantization"] == "not_measured" and row["alpha_bits"] == ""
                            for row in rows))

    def test_rejects_mislabeled_workload(self):
        full = complete_log(compact=True)
        for text in (full.replace("quantization=evu", "quantization=cpu_fallback"),
                     full.replace("workload=rmsnorm_nvfp4_evu", "workload=nvfp4"),
                     full.replace("format=5", "format=4"), full.replace("cases=11", "cases=23"),
                     complete_log(cases=8).replace("format=5", "format=3")):
            with self.assertRaises(ValueError):
                summarize(text)

    def test_rejects_partial_failed_or_duplicate_runs(self):
        full = complete_log()
        first_sample = next(line for line in full.splitlines() if "sample=" in line)
        invalid = (full.replace("[RN] PASS", ""), full.replace("HIT GOOD TRAP", ""),
                   full.replace("errors=0", "errors=1", 1), full.replace(first_sample, ""),
                   full + "\n" + first_sample, full + "\n" + full, full + "\nHIT BAD TRAP")
        for text in invalid:
            with self.assertRaises(ValueError):
                summarize(text)

    def test_compact_output_matches_raw_samples(self):
        self.assertEqual(summarize(complete_log()), summarize(complete_log(compact=True)))
        for cases in (8, 17, 18, 23, 26):
            with self.subTest(cases=cases):
                historical = summarize(complete_log(cases=cases))
                self.assertEqual(len(historical), cases)
                self.assertEqual(historical, summarize(complete_log(cases=cases, compact=True)))
                expected_quantization = "cpu_fallback" if cases in (18, 26) else "evu" if cases == 23 else "not_measured"
                self.assertTrue(all(row["quantization"] == expected_quantization for row in historical))
        text = complete_log(compact=True)
        for invalid in (text.replace("n=3", "n=2", 1), text.replace("min=100", "min=999", 1),
                        text.replace("inst=50", "inst=0", 1)):
            with self.assertRaises(ValueError):
                summarize(invalid)


if __name__ == "__main__":
    unittest.main()
