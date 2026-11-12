#!/usr/bin/env python3
"""Summarize checked EVU RMSNorm runs and historical CPU-fallback runs as CSV."""

import argparse
import csv
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path


SCHEDULED = ("load", "sumsq", "scale_mul_eps", "scale_div_eps", "rsqrt_evu", "normalize",
             "rms_evu", "rms_evu_io")
EXPECTED = {(stage, schedule) for stage in SCHEDULED for schedule in ("row_major", "wave4")}
EXPECTED.add(("empty", "overhead"))
LEGACY_EXPECTED = {(stage, schedule) for stage in
                   ("load", "sumsq", "scale_mul_eps", "scale_div_eps", "normalize", "hybrid")
                   for schedule in ("row_major", "wave4")}
LEGACY_EXPECTED.update((stage, "scalar") for stage in
                       ("empty", "rsqrt_cpu", "amax_cpu", "scales_cpu", "pack_cpu", "quant_cpu"))
PREVIOUS_EXPECTED = LEGACY_EXPECTED | {(stage, schedule) for stage in
                                     ("rsqrt_evu", "rsqrt7_nr2_evu", "rms_evu_quant_cpu", "rms_nr2_quant_cpu")
                                     for schedule in ("row_major", "wave4")}
CASE_SETS = {"17": EXPECTED, "18": LEGACY_EXPECTED, "26": PREVIOUS_EXPECTED}
FUSED_EXPECTED = EXPECTED | {(stage, schedule) for stage in ("amax_evu", "quant_evu", "fused_nvfp4")
                             for schedule in ("row_major", "wave4")}
CASE_SETS["23"] = FUSED_EXPECTED
COMPACT_RMS_EXPECTED = {(stage, schedule) for stage in ("sumsq", "rsqrt_evu", "rms_evu")
                        for schedule in ("row_major", "wave4")}
COMPACT_RMS_EXPECTED.update((("empty", "overhead"), ("scale_div_eps", "row_major")))
COMPACT_FUSED_EXPECTED = COMPACT_RMS_EXPECTED | {("quant_evu", "row_major"),
                                               ("fused_nvfp4", "row_major"), ("fused_nvfp4", "wave4")}
CASE_SETS.update({"8": COMPACT_RMS_EXPECTED, "11": COMPACT_FUSED_EXPECTED})
FIELDS = re.compile(r"(\w+)=([^\s]+)")


def summarize(text):
    headers = re.findall(r"\[RN\] config [^\r\n]*", text)
    if len(headers) != 1:
        raise ValueError("expected exactly one run header; summarize each run separately")
    header = dict(FIELDS.findall(headers[0]))
    if header.get("rows") != "4" or header.get("cols") != "512" or header.get("cases") not in CASE_SETS:
        raise ValueError("unexpected benchmark shape or case count")
    expected = CASE_SETS[header["cases"]]
    if header["cases"] in ("11", "23") and (header.get("format") not in (("5", "6", "7") if header["cases"] == "11" else ("4",)) or
                                    header.get("workload") != "rmsnorm_nvfp4_evu" or
                                    header.get("quantization") != "evu" or "alpha_bits" not in header):
        raise ValueError("expected full EVU RMSNorm/NVFP4 header matching the case format")
    if header["cases"] in ("8", "17") and (header.get("format") not in (("5", "6", "7") if header["cases"] == "8" else ("3",)) or
                                    header.get("workload") != "rmsnorm_evu" or
                                    header.get("quantization") != "not_measured"):
        raise ValueError("expected EVU-only RMSNorm header with quantization=not_measured")
    samples = int(header["samples"])
    if samples < 1 or header.get("input") not in ("fp32", "bf16_rounded_fp32"):
        raise ValueError("invalid sample count or input mode")
    if header.get("format") in ("6", "7") and header.get("warmup") != "0":
        raise ValueError("format 6/7 requires warmup=0")
    if header.get("format") == "7" and header.get("quant_batch") != "16":
        raise ValueError("format 7 requires quant_batch=16")
    if "[RN] PASS" not in text or "HIT GOOD TRAP" not in text:
        raise ValueError("require both [RN] PASS and emulator HIT GOOD TRAP")
    if re.search(r"\[RN\] FAIL|HIT BAD TRAP|ABORT|Assertion.*failed|Assertion fail", text):
        raise ValueError("failure reported in the log")
    grouped = defaultdict(dict)
    aggregates = {}
    for line in re.findall(r"\[RN\] stage=[^\r\n]*", text):
        values = dict(FIELDS.findall(line))
        key = values["stage"], values["schedule"]
        if key not in expected or key in aggregates or int(values["errors"]):
            raise ValueError("unknown, duplicate or failed case: " + str(key))
        if "n" in values:
            minimum, median, maximum = (int(values[name]) for name in ("min", "median", "max"))
            instructions = int(values["inst"])
            if key in grouped or int(values["n"]) != samples or not (0 < minimum <= median <= maximum) or instructions <= 0:
                raise ValueError("invalid aggregate: " + str(key))
            aggregates[key] = minimum, median, maximum, instructions
            continue
        if "inst" in values:
            cycles = int(values["cycles"])
            instructions = int(values["inst"])
            if key in grouped or samples != 1 or cycles <= 0 or instructions <= 0:
                raise ValueError("invalid single measurement: " + str(key))
            aggregates[key] = cycles, cycles, cycles, instructions
            continue
        sample = int(values["sample"])
        cycles = int(values["cycles"])
        instructions = int(values["instructions"])
        if sample in grouped[key]:
            raise ValueError("unknown case or duplicate sample: " + str(key))
        if int(values["errors"]) or cycles <= 0 or instructions <= 0:
            raise ValueError("failed or invalid measurement: " + str(key))
        grouped[key][sample] = cycles, instructions
    if set(grouped) | set(aggregates) != expected:
        raise ValueError("missing benchmark cases")
    for key, measurements in grouped.items():
        if set(measurements) != set(range(samples)):
            raise ValueError("missing or unexpected samples: " + "/".join(key))
        cycles = [measurement[0] for measurement in measurements.values()]
        aggregates[key] = min(cycles), statistics.median(cycles), max(cycles), statistics.median(
            value[1] for value in measurements.values())
    output = []
    for (stage, schedule), (minimum, median, maximum, instructions) in aggregates.items():
        record = {
            "input": header["input"], "alpha_bits": header.get("alpha_bits", ""), "eps_bits": header["eps_bits"],
            "workload": header.get("workload", "rmsnorm_nvfp4_cpu_fallback"),
            "quantization": header.get("quantization", "cpu_fallback"),
            "stage": stage, "schedule": schedule, "samples": samples,
            "cycles_min": minimum, "cycles_median": median, "cycles_max": maximum,
            "cycles_per_row": median / 4,
            "instructions_median": instructions,
            "speedup_vs_row_major": "",
        }
        if schedule == "wave4":
            baseline = aggregates[(stage, "row_major")][1]
            record["speedup_vs_row_major"] = baseline / median
        output.append(record)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    args = parser.parse_args()
    try:
        rows = summarize(args.log.read_text(errors="replace"))
    except (KeyError, ValueError, OSError) as error:
        parser.exit(1, "rmsnorm summary: " + str(error) + "\n")
    writer = csv.DictWriter(sys.stdout, fieldnames=list(rows[0]))
    writer.writeheader()
    writer.writerows(rows)


if __name__ == "__main__":
    main()
