import re
import unittest

from summarize_softmax_fences import format_table, summarize


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


if __name__ == "__main__":
    unittest.main()
