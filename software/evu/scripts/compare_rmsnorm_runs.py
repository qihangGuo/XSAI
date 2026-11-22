#!/usr/bin/env python3
"""Compare matching checked RMSNorm cases from two emulator logs."""

import argparse
import csv
import sys
from pathlib import Path

from summarize_rmsnorm_nvfp4 import summarize


def compare(before, after):
    previous = {(row["stage"], row["schedule"]): row for row in summarize(before)}
    current = {(row["stage"], row["schedule"]): row for row in summarize(after)}
    output = []
    for key in sorted(previous.keys() & current.keys()):
        old, new = previous[key], current[key]
        parameters = ("rows", "cols", "input", "eps_bits") + (("alpha_bits",) if key[0] in ("quant_evu", "fused_nvfp4") else ())
        if any(old[field] != new[field] for field in parameters):
            raise ValueError("different numerical configuration for " + str(key))
        output.append({"stage": key[0], "schedule": key[1], "before_cycles": old["cycles_median"],
                       "after_cycles": new["cycles_median"],
                       "speedup": old["cycles_median"] / new["cycles_median"],
                       "cycle_change_percent": 100 * (new["cycles_median"] / old["cycles_median"] - 1)})
    if not output:
        raise ValueError("no matching measured cases")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    args = parser.parse_args()
    try:
        rows = compare(args.before.read_text(errors="replace"), args.after.read_text(errors="replace"))
    except (ValueError, KeyError, OSError) as error:
        parser.exit(1, "RMSNorm comparison: " + str(error) + "\n")
    writer = csv.DictWriter(sys.stdout, fieldnames=list(rows[0]))
    writer.writeheader()
    writer.writerows(rows)


if __name__ == "__main__":
    main()
