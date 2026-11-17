import unittest
import re

from summarize_rmsnorm_counters import summarize_counters
from summarize_rmsnorm_nvfp4 import CASE_SETS
from test_summarize_rmsnorm_nvfp4 import complete_log


def counter_log(cases=11):
    lines = [complete_log(samples=1, compact=True, cases=cases, single=cases in (1, 11))]
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
    def test_fused_only_uses_only_its_two_measurement_fences(self):
        text = counter_log(cases=1)
        rows = summarize_counters(text)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['fences'], '0:1')
        self.assertEqual(rows[0]['logical_store_bytes'], 1152)
        self.assertEqual(rows[0]['vrf_bank_conflicts'], 3)
        with self.assertRaises((KeyError, ValueError)):
            summarize_counters(text.replace('fences=0:1', 'fences=0:2'))

    def test_fused_only_reports_comparison_work_and_fma_queue_blocking(self):
        text = counter_log(cases=1)
        for fence in (0, 1):
            text += f'\n[EVU-FU] fence={fence} path=fp_cmp issue={1000 + fence * 272} feedback={100 + fence * 7}'
            text += f'\n[EVU-FU] fence={fence} path=fp_fma issue={2000 + fence * 300} qi={500 + fence * 20}'
        row, = summarize_counters(text)
        self.assertEqual(row['cmp_issues'], 272)
        self.assertEqual(row['cmp_feedback_wait'], 7)
        self.assertEqual(row['fma_issues'], 300)
        self.assertEqual(row['fma_queue_idle'], 20)
        self.assertEqual(row['fma_feedback_wait'], '')

    def test_banked_run_reports_timed_diagnostics_without_setup_traffic(self):
        text = counter_log().replace('format=6',
            'format=11 quant_batch=16 quant_vl=16 quant_sew=32 quant_schedule=overlap '
            'lane_sum=1 quant_rename=1 quant_banked=1')
        text = re.sub(r'(\[EVU-EXE\] fence=(\d+) [^\n]*)',
                      lambda m: m[1] + f' dep={10000 + int(m[2]) * 7} resp={20000 + int(m[2]) * 3}', text)
        text = re.sub(r'(\[EVU-FU\] fence=(\d+) path=fp_mxfp [^\n]*)',
                      lambda m: m[1] + f' operand={30000 + int(m[2]) * 2} queue={40000 + int(m[2]) * 5}', text)
        rows = summarize_counters(text)
        self.assertTrue(all(row['dependency_wait'] == 7 and row['vrf_read_response_wait'] == 3 and
                            row['mxfp_operand_wait'] == 2 and row['mxfp_queue_wait'] == 5 for row in rows))
        self.assertTrue(all(row['fma_queue_wait'] == '' for row in rows))
        with self.assertRaisesRegex(ValueError, 'missing counter'):
            summarize_counters(text.replace('dep=10007', '', 1))

    def test_renamed_lane_sum_run_keeps_timed_output_accounting(self):
        text = counter_log().replace('format=6',
            'format=10 quant_batch=16 quant_vl=16 quant_sew=32 quant_schedule=overlap lane_sum=1 quant_rename=1')
        rows = summarize_counters(text)
        self.assertEqual(len(rows), 11)
        self.assertTrue(all(row['logical_store_bytes'] == 1152 for row in rows
                            if row['stage'] in ('quant_evu', 'fused_nvfp4')))

    def test_overlapped_run_keeps_the_same_logical_output_size(self):
        text = counter_log().replace('format=6',
                                     'format=8 quant_batch=16 quant_vl=16 quant_sew=32 quant_schedule=overlap')
        rows = summarize_counters(text)
        self.assertEqual(len(rows), 11)
        self.assertTrue(all(row['logical_store_bytes'] == 1152 for row in rows
                            if row['stage'] in ('quant_evu', 'fused_nvfp4')))

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
