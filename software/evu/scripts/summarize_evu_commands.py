#!/usr/bin/env python3
"""Join optional EVU command summaries by lifetime; aggregate by PC on the host."""

import argparse
from collections import defaultdict
from pathlib import Path
import re
import sys

from summarize_softmax_fences import fields, records


HARDWARE = re.compile(r"\[EVU-(?:CMD-EXEC|CMD|VMU)\]\s+")


def commands(lines, interval=None):
    live, completed = {}, []
    active = interval is None
    saw_start = saw_end = interval is None

    def finish(record):
        if record.get("done") and record["selected"]:
            completed.append(record)

    for text in records(lines, HARDWARE):
        match = HARDWARE.match(text)
        if not match:
            continue
        sample = fields(text[match.end():])
        if match.group().startswith("[EVU-VMU]"):
            if interval is not None:
                fence = int(sample["fence"])
                if fence == interval[0]:
                    active = saw_start = True
                if fence == interval[1]:
                    active = False
                    saw_end = True
            continue
        event = sample["event"]
        if event == "flush":
            for record in live.values():
                finish(record)
            live.clear()
            continue
        vat = int(sample["vat"])
        if event == "accept":
            if vat in live:
                if not live[vat].get("done"):
                    raise ValueError("VAT reused before completion or flush: " + str(vat))
                finish(live[vat])
            live[vat] = dict(sample, selected=active)
        elif vat not in live:
            raise ValueError("command detail without acceptance: VAT " + str(vat))
        elif match.group().startswith("[EVU-CMD-EXEC]"):
            if event not in ("tail", "complete"):
                raise ValueError("unknown execution event: " + event)
            key = "execution_" + event
            if key in live[vat]:
                raise ValueError("duplicate execution event for VAT " + str(vat))
            live[vat][key] = sample
        elif event == "complete":
            record = live[vat]
            if record.get("done") or any(record[key] != sample[key] for key in ("ticket", "job", "epoch", "pc")):
                raise ValueError("completion ownership mismatch for VAT " + str(vat))
            record["done"] = sample
            record["selected"] = record["selected"] and active
        else:
            raise ValueError("unknown command event: " + event)
    for record in live.values():
        finish(record)
    if not saw_start or not saw_end:
        raise ValueError("requested FENCE interval is missing")
    incomplete = sum(record["selected"] and not record.get("done") for record in live.values())
    return completed, incomplete


def format_commands(completed):
    groups = defaultdict(list)
    for record in completed:
        groups[(record["pc"], record["opcode"])].append(record)
    output = ["PC                 opcode count mean_total mean_queue first_wait mean_execute dep_ctx operand_ctx feedback_ctx"]
    for (pc, opcode), rows in sorted(groups.items(), key=lambda item: -sum(int(r["done"]["total"]) for r in item[1])):
        execution = [r["execution_complete"] for r in rows if "execution_complete" in r]
        tails = [r["execution_tail"] for r in rows if "execution_tail" in r]
        def mean(key):
            samples = [int(r[key]) for r in execution if key in r]
            return "{:.1f}".format(sum(samples) / len(samples)) if samples else "-"
        def total(key):
            return str(sum(int(r[key]) for r in tails)) if tails else "-"
        output.append("{} {:>6} {:>5} {:>10.1f} {:>10} {:>10} {:>12} {:>7} {:>11} {:>12}".format(
            pc, opcode, len(rows), sum(int(r["done"]["total"]) for r in rows) / len(rows),
            mean("queue"), mean("first_wait"), mean("execute"), total("dep"), total("operand"), total("feedback")))
    output.append("Latencies overlap across commands; they are not additive kernel stall cycles.")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--fences", help="Only commands fully inside START:END, e.g. 2:3 or 9:10")
    args = parser.parse_args()
    try:
        interval = tuple(map(int, args.fences.split(":"))) if args.fences else None
        if interval is not None and (len(interval) != 2 or interval[0] >= interval[1]):
            raise ValueError("--fences requires START:END with START < END")
        with args.log.open(errors="replace") as log:
            completed, incomplete = commands(log, interval)
        if not completed:
            raise ValueError("no completed commands; run with +evu_cmd_trace=1 and keep the complete log")
        print("Scope: " + ("FENCE " + args.fences if interval else "all traced commands, including warmup/diagnostics"))
        print("\n".join(format_commands(completed)))
        if incomplete:
            print("Incomplete commands excluded: " + str(incomplete), file=sys.stderr)
    except (OSError, KeyError, ValueError) as error:
        print("EVU commands: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
