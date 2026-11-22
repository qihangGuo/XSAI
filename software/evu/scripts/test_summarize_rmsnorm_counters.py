import unittest
import re

from summarize_rmsnorm_counters import summarize_counters
from summarize_rmsnorm_nvfp4 import CASE_SETS
from test_summarize_rmsnorm_nvfp4 import complete_log, tile_log


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
    def test_tile128x64_requires_4608_final_output_bytes(self):
        text = tile_log(counter_log(cases=1)).replace('logical_store=1152', 'logical_store=4608')
        row, = summarize_counters(text)
        self.assertEqual((row['rows'], row['cols']), (128, 64))
        self.assertEqual(row['logical_store_bytes'], 4608)
        with self.assertRaisesRegex(ValueError, '4608'):
            summarize_counters(text.replace('logical_store=4608', 'logical_store=1152'))

    def test_integer_owner_and_completion_diagnostics_are_interval_deltas(self):
        text = counter_log(cases=1)
        for fence in (0, 1):
            text += f'\n[EVU-INTCTX] fence={fence} candidate={100 + fence * 9} raw={50 + fence * 3} destination={20 + fence * 2} independent={30 + fence * 4} second={5 + fence * 8} overlap={2 + fence * 6}'
            text += f'\n[EVU-OWNER] fence={fence} full_release={10 + fence * 7} reuse={3 + fence * 5}'
            text += f'\n[EVU-COMPLETION] fence={fence} write={40 + fence * 13} tracker={50 + fence * 11} transport={60 + fence * 17}'
        row, = summarize_counters(text)
        expected = {'int_context_candidate': 9, 'int_context_raw': 3,
                    'int_context_destination': 2, 'int_context_independent': 4,
                    'int_context_second': 8, 'int_context_overlap': 6,
                    'reduction_full_with_release': 7, 'reduction_owner_reuse': 5,
                    'completion_head_write_wait': 13, 'completion_head_tracker_wait': 11,
                    'completion_head_transport_wait': 17}
        for key, value in expected.items():
            self.assertEqual(row[key], value, key)
        with self.assertRaisesRegex(ValueError, 'missing counter'):
            summarize_counters(text.replace('reuse=8', '', 1))

    def test_integer_divider_dispatch_and_job_diagnostics_use_interval_deltas(self):
        text = counter_log(cases=1)
        for fence in (0, 1):
            text += f'\n[EVU-FU] fence={fence} path=int issue={100 + fence * 886} busy={200 + fence * 1200} dep={30 + fence * 17} queue={40 + fence * 13} perm={9 + fence * 3}'
            text += f'\n[EVU-FU] fence={fence} path=fp_div issue={10 + fence * 32} busy={100 + fence * 900} fu={50 + fence * 80}'
            text += f'\n[EVU-JOB] fence={fence} full={100 + fence * 14} head={20 + fence * 9} release={3 + fence * 2}'
            text += f'\n[EVU-DISPATCH] fence={fence} path=int queue={200 + fence * 31}'
            text += f'\n[EVU-DISPATCH] fence={fence} path=all queue={300 + fence * 45}'
            text += f'\n[EVU-HANDOFF] fence={fence} path=int eligible=100 idle=20 slide={50 + fence * 7}'
        row, = summarize_counters(text)
        for key, expected in {'int_issues': 886, 'int_busy_cycles': 1200, 'int_dependency_wait': 17,
                              'int_queue_wait': 13, 'int_permutation_wait': 3,
                              'div_issues': 32, 'div_busy_cycles': 900,
                              'div_fu_wait': 80, 'job_full': 14, 'job_head_blocked': 9,
                              'job_full_release': 2, 'dispatch_int_blocked': 31,
                              'dispatch_all_blocked': 45, 'int_slide_prefetch_eligible': 7}.items():
            self.assertEqual(row[key], expected, key)
        self.assertEqual(row['dispatch_store_blocked'], '')

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
