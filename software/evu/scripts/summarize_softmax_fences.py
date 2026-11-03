#!/usr/bin/env python3
"""Extract formal online-softmax results and counter deltas from FENCE snapshots."""

import argparse
import re
import sys
from pathlib import Path


FIELDS = re.compile(r"(\w+)=[ \t]*([^\s\[]+)")
RECORD = re.compile(r"\[EVU-(VMU|EXE|FU|PREFLIGHT)\]\s+|\[OS\]\s+(?=(?:r|first)=)")
HARDWARE = re.compile(r"\[EVU-(?:VMU|EXE|FU|PREFLIGHT)\]\s+")
GUEST = re.compile(r"\[OS\]\s+(?:r=|first=|DONE\b|PASS\b)[^\r\n]*(?:\r?\n)?")
PATHS = ("fp_fma", "fp_cmp", "fp_exp")
REQUIRED = {"VMU", "EXE"} | {"FU:" + path for path in PATHS}


def fields(text):
    return dict(FIELDS.findall(text))


def counter_delta(start, end, key):
    if key not in start or key not in end:
        raise ValueError("missing counter: " + key)
    value = int(end[key]) - int(start[key])
    if value < 0:
        raise ValueError("counter reset inside formal interval: " + key)
    return value


def summarize_run(header, fences, results):
    warmup, diagnostic = int(header["w"]), int(header["diag"])
    per_case = 2 + 2 * warmup + 3 * diagnostic
    ids = sorted(fences)
    expected = per_case * len(results)
    if not results or len(ids) != expected or any(b != a + 1 for a, b in zip(ids, ids[1:])):
        raise ValueError("expected {} consecutive FENCE snapshots for {} completed cases, got {}; "
                         "run with -- +evu_mem_trace=1 and keep the full log".format(
                             expected, len(results), len(ids)))
    output = []
    for index, result in enumerate(results):
        offset = index * per_case + 2 * warmup
        start_id, end_id = ids[offset:offset + 2]
        start, end = fences[start_id], fences[end_id]
        if not REQUIRED.issubset(start) or not REQUIRED.issubset(end):
            raise ValueError("incomplete formal FENCE snapshot")
        values = ["first=" + result["first"], "c=" + result["c"],
                  "fences={}:{}".format(start_id, end_id),
                  "fence_cycles=" + str(counter_delta(start["VMU"], end["VMU"], "t"))]
        for key in ("dpw", "defer", "used", "drop"):
            if key in start["EXE"] or key in end["EXE"]:
                values.append(key + "=" + str(counter_delta(start["EXE"], end["EXE"], key)))
        for key in ("i", "warm_c", "epc", "pre", "full", "drain", "empty", "fl", "err"):
            if key in result:
                values.append(key + "=" + result[key])
        output.append("[OS-INT] " + " ".join(values))
        for kind, prefix, keys in (
            ("VMU", "OS-VMU", ("cmd", "tag", "get", "put", "partial", "full", "ack", "entry", "win", "merge",
                               "rmwget", "rmwmerge")),
            ("EXE", "OS-EXE", ("dep", "operand", "feedback", "perm", "wb", "fu", "grant", "resp", "e0",
                                "early", "rpf", "bank", "wrblk", "vfw", "vnew")),
        ):
            values = ["first=" + result["first"]]
            for key in keys:
                if key in start[kind] or key in end[kind]:
                    values.append(key + "=" + str(counter_delta(start[kind], end[kind], key)))
            output.append("[" + prefix + "] " + " ".join(values))
        for path in PATHS:
            kind = "FU:" + path
            values = ["first=" + result["first"], "path=" + path]
            for key in ("issue", "dep", "operand", "feedback", "wb", "fu", "queue",
                        "idle", "arb", "qi", "slot", "pri", "nowork", "loadwait", "fwait"):
                if key in start[kind] or key in end[kind]:
                    values.append(key + "=" + str(counter_delta(start[kind], end[kind], key)))
            output.append("[OS-FU] " + " ".join(values))
        if "PREFLIGHT" in start or "PREFLIGHT" in end:
            if "PREFLIGHT" not in start or "PREFLIGHT" not in end:
                raise ValueError("incomplete formal preflight snapshot")
            values = ["first=" + result["first"]]
            for key in ("cmd", "busy", "checks", "reads", "writes", "fullstore", "lookupstall",
                        "tlbreq", "tlbmiss", "sbwait", "outputwait", "serialwait"):
                values.append(key + "=" + str(counter_delta(start["PREFLIGHT"], end["PREFLIGHT"], key)))
            output.append("[OS-PREFLIGHT] " + " ".join(values))
    return output


def records(lines):
    # UART can interrupt even a hardware field name ("no[OS] ...\nwork=").
    # Extract complete guest lines and reconnect the interrupted hardware text.
    pending = ""
    for line in lines:
        for guest in GUEST.finditer(line):
            if re.match(r"\[OS\]\s+r=", guest.group()) and pending:
                yield pending
                pending = ""
            yield guest.group()
        clean = GUEST.sub("", line)
        matches = list(HARDWARE.finditer(clean))
        if matches:
            if pending:
                yield pending + clean[:matches[0].start()]
            for index, match in enumerate(matches[:-1]):
                yield clean[match.start():matches[index + 1].start()]
            pending = clean[matches[-1].start():]
        elif pending:
            if clean.lstrip().startswith("["):
                yield pending
                pending = ""
            else:
                pending += clean
    if pending:
        yield pending


def summarize(lines):
    header = None
    fences = {}
    results = []
    output = []
    # Guest UART and simulator stdout may interleave, even within one line.
    # Match records separately and wait for the full run before assigning intervals.
    for line in records(lines):
        matches = list(RECORD.finditer(line))
        for index, match in enumerate(matches):
            stop = matches[index + 1].start() if index + 1 < len(matches) else len(line)
            sample = fields(line[match.end():stop])
            if match.group(1) is None:
                if "r" in sample:
                    if header is not None:
                        output.extend(summarize_run(header, fences, results))
                    header, fences, results = sample, {}, []
                elif header is not None:
                    if any(result["first"] == sample["first"] for result in results):
                        raise ValueError("duplicate result for first=" + sample["first"])
                    results.append(sample)
            elif header is not None:
                fence = int(sample["fence"])
                kind = match.group(1)
                if kind == "FU":
                    kind += ":" + sample["path"]
                group = fences.setdefault(fence, {})
                if kind in group:
                    raise ValueError("duplicate snapshot at fence {}: {}".format(fence, kind))
                group[kind] = sample
    if header is not None:
        output.extend(summarize_run(header, fences, results))
    if not output:
        raise ValueError("no completed online-softmax cases with FENCE snapshots")
    return output


def format_table(summary):
    runs, cases = [], []
    for line in summary:
        tag, _, text = line.partition("] ")
        sample = fields(text)
        if tag == "[OS-INT":
            if any(case["INT"]["first"] == sample["first"] for case in cases):
                runs.append(cases)
                cases = []
            cases.append({"INT": sample})
        else:
            kind = tag[len("[OS-"):]
            if kind == "FU":
                kind += ":" + sample["path"]
            cases[-1][kind] = sample
    if cases:
        runs.append(cases)

    sections = (
        ("Timing", (("Kernel cycles", "INT", "c"),
                    ("Warmup cycles", "INT", "warm_c"),
                    ("Elements/cycle", "INT", "epc"),
                    ("Diagnostic drain cycles", "INT", "drain"),
                    ("FP flags (1 = inexact)", "INT", "fl"),
                    ("EVU errors", "INT", "err"))),
        ("Formal interval", (("FENCE pair", "INT", "fences"),
                             ("Snapshot cycles", "INT", "fence_cycles"),
                             ("Address/permission checks", "PREFLIGHT", "checks"),
                             ("Preflight busy cycles", "PREFLIGHT", "busy"),
                             ("CPU waiting for preflight", "PREFLIGHT", "outputwait"),
                             ("TLB miss responses", "PREFLIGHT", "tlbmiss"),
                             ("Average live VMU commands", "VMU", "cmd"),
                             ("Average live memory tags", "VMU", "tag"),
                             ("External Get requests", "VMU", "get"),
                             ("External Put requests", "VMU", "put"),
                             ("Partial-store RMW Gets", "VMU", "rmwget"),
                             ("VMU entry stall cycles", "VMU", "entry"))),
        ("Execution", (("VRF read grant wait", "EXE", "grant"),
                       ("VRF read response wait", "EXE", "resp"),
                       ("VRF read bank conflicts", "EXE", "bank"),
                       ("VRF writes blocking reads", "EXE", "wrblk"),
                       ("Full-EG vector forwards", "EXE", "vfw"),
                       ("FMA issue count", "FU:fp_fma", "issue"),
                       ("FMA cycles without work", "FU:fp_fma", "nowork"),
                       ("EXP2 issue count", "FU:fp_exp", "issue"))),
    )

    def cell(case, kind, key):
        value = case.get(kind, {}).get(key)
        if value is None:
            return "-"
        if kind == "VMU" and key in ("cmd", "tag"):
            cycles = int(case["INT"]["fence_cycles"])
            return "{:.2f}".format(int(value) / cycles) if cycles else "-"
        if key in ("epc", "fences"):
            return value
        return "{:,}".format(int(value))

    output = []
    for index, run in enumerate(runs, 1):
        if output:
            output.append("")
        if len(runs) > 1:
            output.append("Run {}".format(index))
        output.append("{:<32}".format("Metric") + "".join(
            "{:>14}".format("first=" + case["INT"]["first"]) for case in run))
        output.append("-" * (32 + 14 * len(run)))
        for title, rows in sections:
            output.append(title)
            for label, kind, key in rows:
                output.append("  {:<30}".format(label) + "".join(
                    "{:>14}".format(cell(case, kind, key)) for case in run))
    output.append("Counter rows overlap; do not add them. '-' means unavailable.")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--format", choices=("records", "table"), default="records",
                        help="compact key=value records (default) or a comparison table")
    args = parser.parse_args()
    try:
        with args.log.open(errors="replace") as log:
            summary = summarize(log)
        for line in format_table(summary) if args.format == "table" else summary:
            print(line)
    except (OSError, KeyError, ValueError) as error:
        print("softmax intervals: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
