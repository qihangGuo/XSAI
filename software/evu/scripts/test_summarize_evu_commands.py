import unittest

from summarize_evu_commands import commands, format_commands


def accept(ticket=0, epoch=0, pc="0x80000004"):
    return f"[EVU-CMD] event=accept ticket={ticket} vat=3 job=2 epoch={epoch} pc={pc} opcode=8 t=100\n"


def done(ticket=0, epoch=0, pc="0x80000004"):
    return f"[EVU-CMD] event=complete ticket={ticket} vat=3 job=2 epoch={epoch} pc={pc} total=40 t=140\n"


TAIL = "[EVU-CMD-EXEC] event=tail vat=3 op=1 context=2 residence=20 dep=2 operand=3 feedback=0 wb=1 execution=2\n"
EXEC = "[EVU-CMD-EXEC] event=complete vat=3 op=1 queue=10 execute=28\n"


class CommandSummaryTest(unittest.TestCase):
    def test_completion_and_execution_print_order_and_reuse(self):
        for detail in ((EXEC, done()), (done(), EXEC)):
            result, incomplete = commands([accept(), TAIL, *detail, accept(1, 1), done(1, 1)])
            self.assertEqual(len(result), 2)
            self.assertEqual(incomplete, 0)
            self.assertEqual(result[0]["execution_complete"]["execute"], "28")
            self.assertIn("40.0", format_commands(result)[1])

    def test_flush_discards_old_owner_before_reuse(self):
        result, incomplete = commands([accept(), TAIL, "[EVU-CMD] event=flush t=120\n",
                                       accept(1, 1), done(1, 1)])
        self.assertEqual([r["ticket"] for r in result], ["1"])
        self.assertNotIn("execution_tail", result[0])
        self.assertEqual(incomplete, 0)

    def test_matching_fences_exclude_warmup_and_crossing_commands(self):
        lines = [accept(), "[EVU-VMU] fence=2 t=110\n", done(), accept(1), TAIL, EXEC,
                 done(1), "[EVU-VMU] fence=3 t=200\n", accept(2), done(2)]
        result, _ = commands(lines, (2, 3))
        self.assertEqual([r["ticket"] for r in result], ["1"])

    def test_reject_stale_ticket_and_live_reuse(self):
        for lines in ([accept(), done(1)], [accept(), accept(1)]):
            with self.assertRaises(ValueError):
                commands(lines)

    def test_uart_split_and_unfinished_command(self):
        result, incomplete = commands([accept().replace("pc=", "p[OS] DONE\nc="), TAIL, EXEC, done(), accept(1)])
        self.assertEqual(len(result), 1)
        self.assertEqual(incomplete, 1)
        with self.assertRaisesRegex(ValueError, "FENCE interval"):
            commands([accept(), done()], (2, 3))


if __name__ == "__main__":
    unittest.main()
