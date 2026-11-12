import unittest
import re

from summarize_rmsnorm_counters import summarize_counters
from summarize_rmsnorm_nvfp4 import CASE_SETS
from test_summarize_rmsnorm_nvfp4 import complete_log


def counter_log(cases=11):
    lines = [complete_log(samples=1, compact=True, cases=cases, single=cases == 11)]
    for index, (stage, schedule) in enumerate(sorted(CASE_SETS[str(cases)])):
        start, end = 2 * index, 2 * index + 1
        stored = 1152 if stage in ("quant_evu", "fused_nvfp4") else 0
        for fence, extra in ((start, 0), (end, 1)):
            base = index * 10000
            lines.append(f"[EVU-EXE] fence={fence} bank={base + extra * 3} wb={base + extra * 5} wrblk={base + extra}")
            lines.append(f"[EVU-VMU] fence={fence} t={base + extra * 100}")
            lines.append(f"[EVU-MEM] fence={fence} logical_store={base + extra * stored}")
            for path in ("fp_xx8", "fp_mxfp"):
                lines.append(f"[EVU-FU] fence={fence} path={path} issue={base + extra * 2} wb={base + extra}")
        lines.append(f"[RN-SAMPLE] stage={stage} schedule={schedule} sample=0 cycles=100 instructions=50 errors=0 fences={start}:{end}")
    return "\n".join(lines)


class RmsnormCountersTest(unittest.TestCase):
    def test_batched_run_keeps_the_same_logical_output_size(self):
        text = counter_log().replace("format=6", "format=7 quant_batch=16")
        rows = summarize_counters(text)
        self.assertEqual(len(rows), 11)
        self.assertTrue(all(row["logical_store_bytes"] == 1152 for row in rows
                            if row["stage"] in ("quant_evu", "fused_nvfp4")))

    def test_uses_timed_fence_deltas_and_checks_final_output_bytes(self):
        for cases in (11, 23):
            with self.subTest(cases=cases):
                rows = summarize_counters(counter_log(cases))
                self.assertEqual(len(rows), cases)
                self.assertTrue(all(row["vrf_bank_conflicts"] == 3 and row["writeback_wait"] == 5 for row in rows))
                self.assertTrue(all(row["logical_store_bytes"] == 1152 for row in rows if row["stage"] == "fused_nvfp4"))

    def test_rejects_missing_and_reset_counters(self):
        text = counter_log()
        for broken in (text.replace("path=fp_xx8", "path=missing"),
                       text.replace("bank=3", "bank=-1", 1),
                       text.replace("fences=0:1", "fences=0:2", 1),
                       text.replace("logical_store=1152", "logical_store=8192", 1)):
            if broken == text:
                continue
            with self.assertRaises((KeyError, ValueError)):
                summarize_counters(broken)

    def test_guest_line_can_interrupt_hardware_field(self):
        text = counter_log()
        sample = next(line for line in text.splitlines() if line.startswith("[RN-SAMPLE]"))
        text = text.replace(sample + "\n", "", 1)
        text = text.replace("bank=3", "ba" + sample + "\nnk=3", 1)
        self.assertEqual(len(summarize_counters(text)), 11)

    def test_rejects_intermediate_store_traffic(self):
        text = counter_log()
        end = re.search(r"\[RN-SAMPLE\] stage=fused_nvfp4[^\n]*fences=\d+:(\d+)", text)[1]
        pattern = rf"(\[EVU-MEM\] fence={end} logical_store=)(\d+)"
        text = re.sub(pattern, lambda match: match[1] + str(int(match[2]) + 8192), text)
        with self.assertRaisesRegex(ValueError, "1152"):
            summarize_counters(text)


if __name__ == "__main__":
    unittest.main()
