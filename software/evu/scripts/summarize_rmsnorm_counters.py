#!/usr/bin/env python3
"""Join measured RMSNorm intervals with EVU hardware counter snapshots."""

import argparse
import csv
import re
import sys
from pathlib import Path

from summarize_rmsnorm_nvfp4 import FIELDS, summarize
from summarize_softmax_fences import records, fields, counter_delta, snapshot_key


def summarize_counters(text):
    aggregates = summarize(text)
    expected = {(row["stage"], row["schedule"], sample)
                for row in aggregates for sample in range(row["samples"])}
    samples = [dict(FIELDS.findall(line)) for line in
               re.findall(r"\[RN-SAMPLE\][^\r\n]*", text)]
    hardware = re.sub(r"\[RN(?:-SAMPLE)?\][^\r\n]*(?:\r?\n)?", "", text)
    snapshots = {}
    for record in records(hardware.splitlines(keepends=True)):
        match = re.match(r"\[EVU-(EXE|FU|MEM|VMU)\]", record)
        if not match:
            continue
        sample = fields(record)
        key = int(sample["fence"]), snapshot_key(match[1], sample)
        if key in snapshots:
            raise ValueError("duplicate hardware snapshot")
        snapshots[key] = sample
    output, seen = [], set()
    counters = (("EXE", "bank", "vrf_bank_conflicts"), ("EXE", "wb", "writeback_wait"),
                ("EXE", "wrblk", "writes_blocking_reads"), ("VMU", "t", "fence_cycles"),
                ("MEM", "logical_store", "logical_store_bytes"),
                ("FU:fp_xx8", "issue", "xx8_issues"), ("FU:fp_mxfp", "issue", "mxfp_issues"),
                ("FU:fp_xx8", "wb", "xx8_writeback_wait"), ("FU:fp_mxfp", "wb", "mxfp_writeback_wait"))
    diagnostics = (("EXE", "dep", "dependency_wait"), ("EXE", "operand", "operand_wait"),
                   ("EXE", "feedback", "feedback_wait"), ("EXE", "perm", "permutation_wait"),
                   ("EXE", "grant", "vrf_read_grant_wait"), ("EXE", "resp", "vrf_read_response_wait"),
                   ("EXE", "vfw", "vector_forwards"), ("EXE", "used", "prefetch_used"),
                   ("VMU", "ack", "vmu_ack_wait"),
                   ("FU:fp_fma", "queue", "fma_queue_wait"), ("FU:fp_fma", "idle", "fma_idle_with_work"),
                   ("FU:fp_cmp", "queue", "cmp_queue_wait"), ("FU:fp_cmp", "idle", "cmp_idle_with_work"),
                   ("FU:fp_xx8", "idle", "xx8_idle_with_work"),
                   ("FU:fp_mxfp", "operand", "mxfp_operand_wait"),
                   ("FU:fp_mxfp", "queue", "mxfp_queue_wait"), ("FU:fp_mxfp", "idle", "mxfp_idle_with_work"))
    for sample in samples:
        key = sample["stage"], sample["schedule"], int(sample["sample"])
        if key not in expected or key in seen or int(sample["errors"]):
            raise ValueError("unknown, duplicate or failed timed sample")
        seen.add(key)
        start, end = map(int, sample["fences"].split(":"))
        if end != start + 1:
            raise ValueError("expected exactly two adjacent measurement fences")
        result = {"stage": key[0], "schedule": key[1], "sample": key[2],
                  "cycles": int(sample["cycles"]), "fences": sample["fences"]}
        for kind, field, label in counters:
            result[label] = counter_delta(snapshots[start, kind], snapshots[end, kind], field)
        for kind, field, label in diagnostics:
            before, after = snapshots.get((start, kind), {}), snapshots.get((end, kind), {})
            result[label] = counter_delta(before, after, field) if field in before or field in after else ""
        if key[0] in ("quant_evu", "fused_nvfp4") and result["logical_store_bytes"] != 1152:
            raise ValueError("NVFP4 timed interval must store exactly 1152 logical bytes")
        output.append(result)
    if seen != expected:
        raise ValueError("require all timed samples: build EVU_RMS_VERBOSE=1 and run +evu_mem_trace=1")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    args = parser.parse_args()
    try:
        rows = summarize_counters(args.log.read_text(errors="replace"))
    except (KeyError, ValueError, OSError) as error:
        parser.exit(1, "RMSNorm counters: " + str(error) + "\n")
    writer = csv.DictWriter(sys.stdout, fieldnames=list(rows[0]))
    writer.writeheader()
    writer.writerows(rows)


if __name__ == "__main__":
    main()
