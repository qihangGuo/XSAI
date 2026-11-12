import re
import unittest

from summarize_softmax_fences import DIAGNOSTICS, format_table, summarize


def case(first, base, warmup=1, diagnostic=1):
    values = [0]
    if warmup:
        values += [1000, 1000]
    values += [values[-1] + 10]
    if diagnostic:
        values += [values[-1], values[-1] + 100, values[-1] + 100]
    lines = []
    for i, value in enumerate(values):
        fence = base + i
        lines.append("[EVU-VMU] fence={} t={} ack={}\n".format(fence, 100 * i, value))
        lines.append("[EVU-EXE] fence={} dpw=0 defer={} used={} drop=0\n".format(fence, value, value))
        for path in ("fp_fma", "fp_cmp", "fp_exp"):
            lines.append("[EVU-FU] fence={} path={} issue={} idle={} arb=0 qi=0 slot=0 pri=0\n".format(
                fence, path, value, value))
    lines.append("[OS] first={} c=137\n".format(first))
    return lines


class SoftmaxFenceSummaryTest(unittest.TestCase):
    def test_formal_intervals_exclude_warmup_and_diagnostic(self):
        lines = ["[OS] r=32 w=1 diag=1\n"] + case(0, 40) + case(1, 47)
        result = summarize(lines)
        self.assertEqual(len(result), 12)
        self.assertIn("first=0 c=137 fences=42:43", result[0])
        self.assertIn("used=10", result[0])
        self.assertIn("ack=10", result[1])
        self.assertIn("issue=10 idle=10", result[3])
        self.assertIn("first=1 c=137 fences=49:50", result[6])

    def test_disabled_warmup_and_diagnostic(self):
        result = summarize(["[OS] r=32 w=0 diag=0\n"] + case(1, 7, 0, 0))
        self.assertIn("fences=7:8", result[0])
        self.assertIn("issue=10", result[3])

    def test_padded_fields_and_guest_results_interleaved_before_case_end(self):
        lines = case(0, 0) + case(1, 7)
        first = lines.pop(lines.index("[OS] first=0 c=137\n"))
        lines[21] = lines[21].rstrip("\n") + first
        result = summarize(["[OS] r=32 w=1 diag=1\n"] +
                           [re.sub(r"=(?=\d)", "=       ", line) for line in lines])
        self.assertIn("fences=2:3", result[0])
        self.assertIn("ack=10", result[1])
        self.assertIn("fences=9:10", result[6])

    def test_bank_counters_and_execution_waits_are_differenced(self):
        lines = case(0, 0)
        for index, line in enumerate(lines):
            if line.startswith("[EVU-EXE]"):
                value = index // 5
                lines[index] = line.rstrip("\n") + " bank={} wrblk={} grant={}\n".format(
                    value * 3, value * 5, value * 7)
        result = summarize(["[OS] r=32 w=1 diag=1\n"] + lines)
        self.assertIn("grant=7", result[2])
        self.assertIn("bank=3 wrblk=5", result[2])

    def test_rmw_and_forwarding_diagnostics_are_differenced(self):
        lines = case(0, 0)
        for index, line in enumerate(lines):
            value = index // 5
            if line.startswith("[EVU-VMU]"):
                extra = " rmwget={} rmwmerge={}".format(value * 2, value * 3)
            elif line.startswith("[EVU-EXE]"):
                extra = " vfw={} vnew={}".format(value * 4, value * 5)
            elif line.startswith("[EVU-FU]"):
                extra = " nowork={} loadwait={} fwait={}".format(value * 6, value * 7, value * 8)
            else:
                continue
            lines[index] = line.rstrip("\n") + extra + "\n"
        result = summarize(["[OS] r=32 w=1 diag=1\n"] + lines)
        self.assertIn("rmwget=2 rmwmerge=3", result[1])
        self.assertIn("vfw=4 vnew=5", result[2])
        self.assertIn("nowork=6 loadwait=7 fwait=8", result[3])

    def test_concatenated_runs_keep_their_own_fence_numbering(self):
        run = ["[OS] r=32 w=1 diag=1\n"] + case(0, 0)
        self.assertEqual(len(summarize(run + run)), 12)

    def test_uart_can_split_a_hardware_field_name_and_value(self):
        text = "".join(["[OS] r=32 w=1 diag=1\n"] + case(0, 0) + case(1, 7))
        for first, fence in ((0, 3), (1, 10)):
            guest = "[OS] first={} c=137\n".format(first)
            text = text.replace(guest, "")
            prefix = "[EVU-FU] fence={} path=fp_fma".format(fence)
            lines = text.splitlines(keepends=True)
            for index, line in enumerate(lines):
                if line.startswith(prefix):
                    lines[index] = (line.rstrip("\n") + " no" + guest +
                                    "[OS] DONE\nwork=50 fwait=[OS] DONE\n0\n")
            text = "".join(lines)
        # Include the fields at all other snapshots, where no UART split occurs.
        lines = text.splitlines(keepends=True)
        for index, line in enumerate(lines):
            if line.startswith("[EVU-FU]") and "path=fp_fma" in line and " no[OS]" not in line:
                lines[index] = line.rstrip("\n") + " nowork=50 fwait=0\n"
        result = summarize(lines)
        self.assertIn("nowork=0 fwait=0", result[3])
        self.assertIn("nowork=0 fwait=0", result[9])

    def test_optional_preflight_counters_match_the_formal_fence_interval(self):
        lines = case(0, 0)
        for fence in range(7):
            values = " ".join("{}={}".format(key, fence * (index + 1)) for index, key in enumerate(
                ("cmd", "busy", "checks", "reads", "writes", "fullstore", "lookupstall",
                 "tlbreq", "tlbmiss", "sbwait", "outputwait", "serialwait")))
            lines.append("[EVU-PREFLIGHT] fence={} {}\n".format(fence, values))
        result = summarize(["[OS] r=32 w=1 diag=1\n"] + lines)
        self.assertEqual(len(result), 7)
        self.assertIn("[OS-PREFLIGHT] first=0 cmd=1 busy=2 checks=3", result[-1])
        self.assertIn("outputwait=11 serialwait=12", result[-1])

    def test_counter_reset_inside_formal_interval_fails(self):
        lines = [line.replace("fence=3 t=300 ack=1010", "fence=3 t=300 ack=1")
                 for line in case(0, 0)]
        with self.assertRaisesRegex(ValueError, "counter reset"):
            summarize(["[OS] r=32 w=1 diag=1\n"] + lines)

    def test_missing_snapshots_fail_instead_of_misattributing(self):
        with self.assertRaisesRegex(ValueError, "evu_mem_trace"):
            summarize(["[OS] r=32 w=1 diag=1\n", "[OS] first=0 c=137\n"])

    def test_missing_fu_snapshot_fails(self):
        lines = [line for line in case(0, 0) if "fence=3 path=fp_exp" not in line]
        with self.assertRaisesRegex(ValueError, "incomplete"):
            summarize(["[OS] r=32 w=1 diag=1\n"] + lines)

    def test_missing_preflight_snapshot_fails(self):
        lines = case(0, 0) + ["[EVU-PREFLIGHT] fence=2 cmd=1\n"]
        with self.assertRaisesRegex(ValueError, "incomplete formal preflight"):
            summarize(["[OS] r=32 w=1 diag=1\n"] + lines)

    def test_guest_timing_fields_survive_uart_interleaving(self):
        lines = case(0, 0)
        guest = lines.pop().replace("c=137", "c=137 i=42 warm_c=140 epc=1.234 "
                                   "pre=120 full=130 drain=10 empty=2 fl=1 err=0")
        lines[17] = lines[17].rstrip("\n") + guest
        result = summarize(["[OS] r=32 w=1 diag=1\n"] + lines)
        self.assertIn("i=42 warm_c=140 epc=1.234", result[0])
        self.assertIn("drain=10 empty=2 fl=1 err=0", result[0])

    def test_table_compares_cases_and_averages_occupancy(self):
        lines = case(0, 0) + case(1, 7)
        for index, line in enumerate(lines):
            if line.startswith("[EVU-VMU]"):
                value = int(re.search(r"t=(\d+)", line).group(1))
                lines[index] = line.rstrip("\n") + " cmd={} tag={}\n".format(value * 2, value * 4)
        result = format_table(summarize(["[OS] r=32 w=1 diag=1\n"] + lines))
        self.assertIn("first=0", result[0])
        self.assertIn("first=1", result[0])
        commands = next(line for line in result if "Average live VMU commands" in line)
        self.assertEqual(commands.split()[-2:], ["2.00", "2.00"])
        tags = next(line for line in result if "Average live memory tags" in line)
        self.assertEqual(tags.split()[-2:], ["4.00", "4.00"])
        missing = next(line for line in result if "TLB miss responses" in line)
        self.assertEqual(missing.split()[-2:], ["-", "-"])
        self.assertTrue(all(len(line) <= 80 for line in result))

    def test_table_keeps_concatenated_runs_separate(self):
        run = ["[OS] r=32 w=1 diag=1\n"] + case(0, 0) + case(1, 7)
        result = format_table(summarize(run + run))
        self.assertIn("Run 1", result)
        self.assertIn("Run 2", result)

    def test_instruction_stage_and_memory_diagnostics(self):
        lines = case(0, 0)
        for fence in range(7):
            for tag, (labels, keys) in DIAGNOSTICS.items():
                values = {"path": "fp_fma", "stage": "fold", "op": "red_sum", "bucket": "2"}
                text = " ".join(label + "=" + values[label] for label in labels)
                text += " " + " ".join(key + "=" + str((i + 1) * fence) for i, key in enumerate(keys))
                lines.append("[EVU-{}] fence={} {}\n".format(tag, fence, text))
        result = summarize(["[OS] r=32 w=1 diag=1\n"] + lines)
        self.assertIn("[OS-OP] first=0 op=red_sum inst=1 issue=2 e16=3 e32=4 dep=5 operand=6 feedback=7 completed=8 latency=9", result)
        self.assertTrue(any("path=fp_fma stage=fold issue=1" in line for line in result))
        self.assertTrue(any("bucket=2 load=1 store=2" in line for line in result))
        table = format_table(result)
        self.assertTrue(any("red_sum mean cycles" in line and "1.1" in line for line in table))
        missing = [line for line in lines if not line.startswith("[EVU-READ] fence=3 ")]
        with self.assertRaisesRegex(ValueError, "incomplete formal diagnostic"):
            summarize(["[OS] r=32 w=1 diag=1\n"] + missing)

    def test_new_records_reconnect_interrupted_uart_and_preserve_dispatch(self):
        lines = case(0, 0)
        for i, line in enumerate(lines):
            if line.startswith("[EVU-FU]"):
                lines[i] = line.rstrip() + " dispatch={}\n".format(i // 5)
        keys = DIAGNOSTICS["SCHED"][1]
        for fence in range(7):
            text = " ".join(key + "=" + str(fence) for key in keys)
            lines.append("[EVU-SCHED] fence={} path=fp_fma {}\n".format(fence, text))
        text = "".join(["[OS] r=32 w=1 diag=1\n"] + lines)
        text = text.replace("ordinary_idle=3", "ordinary_[OS] DONE\nidle=3")
        result = summarize(text.splitlines(keepends=True))
        self.assertIn("dispatch=1", result[3])
        self.assertTrue(any("ordinary_idle=1" in line for line in result))

    def test_geometry_is_not_differenced_and_must_match(self):
        lines = case(0, 0)
        for fence in range(7):
            lines.append("[EVU-CONFIG] fence={} dlen=512 mlen=512 vlen=4096 fma_contexts=2 reduction_contexts=2\n".format(fence))
        result = summarize(["[OS] r=32 w=1 diag=1\n"] + lines)
        self.assertIn("[OS-CONFIG] first=0 dlen=512 mlen=512 vlen=4096 fma_contexts=2 reduction_contexts=2", result)
        changed = [line.replace("dlen=512", "dlen=256") if line.startswith("[EVU-CONFIG] fence=3 ") else line
                   for line in lines]
        with self.assertRaisesRegex(ValueError, "configuration changed"):
            summarize(["[OS] r=32 w=1 diag=1\n"] + changed)

    def test_read_hints_use_formal_deltas_and_retired_matrix_records_are_ignored(self):
        lines = case(0, 0)
        for fence in range(7):
            lines.append("[EVU-BRIDGE] fence={} mte_block={} evu_block={} bridge_block={} "
                         "occ0={} occ1={} occ2={} tiles={} gap1={} gap2_3={} gap4_7={} gap8={}\n".format(
                             fence, *([fence * 3] * 11)))
            lines.append("[EVU-HANDOFF] fence={} path=fp_exp eligible={} idle={}\n".format(fence, fence * 4, fence))
            lines.append("[EVU-TAIL] fence={} path=fp_fma stage=fold ready_wait={} return_issue={}\n".format(
                fence, fence * 5, fence * 2))
            lines.append("[EVU-MATRIX-TASK] task={} peak=2 full=12 burst=3\n".format(fence))
        result = summarize(["[OS] r=32 w=1 diag=1\n"] + lines)
        self.assertFalse(any("[OS-BRIDGE]" in line for line in result))
        self.assertTrue(any("path=fp_exp eligible=4 idle=1" in line for line in result))
        self.assertTrue(any("stage=fold ready_wait=5 return_issue=2" in line for line in result))
        self.assertFalse(any("peak=" in line for line in result))


if __name__ == "__main__":
    unittest.main()
