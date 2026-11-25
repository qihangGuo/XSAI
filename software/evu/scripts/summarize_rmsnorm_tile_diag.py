"""Compare four fresh EMU runs of rmsnorm_nvfp4_tile_diag_evu_test."""
import argparse
from pathlib import Path
import re

from summarize_softmax_fences import fields


def require(ok, message):
    if not ok:
        raise ValueError(message)


def read_log(path, mode, with_trace=True):
    text = path.read_text(errors="replace")
    guest = re.findall(r"\[RN-DIAG\] [^\r\n]+", text)
    require(len(guest) == 1 and "HIT GOOD TRAP" in text, "missing diagnostic result or GOOD TRAP")
    result = fields(guest[0])
    require(result["mode"] == mode and result["errors"] == "0", "wrong guest or failed run")
    stores = 0 if mode in "LR" else 72
    diff = re.search(r"\[DiffTest\]\[EVU\][^\n]+", text)
    stats = fields(diff[0]) if diff else {}
    require(int(stats.get("checked_stores", -1)) == stores and stats.get("pending") == "0" and
            int(stats.get("checked_completions", 0)) > 0,
            "missing/incomplete EVU difftest")
    rows = [fields(x) for x in re.findall(r"\[RN-ROW\] [^\r\n]+", text)]
    if mode in "LR":
        require([int(r["group"]) for r in rows] == [0] and
                all(r["errors"] == "0" and int(r["cycles"]) > 0 for r in rows), "bad row samples")
    else:
        require(int(result["cycles"]) > 0, "missing full-tile timing")
        out = re.findall(r"\[RN-OUTPUT\] scales=([0-9a-fA-F]+) packed=([0-9a-fA-F]+)", text)
        require(len(out) == 1, "missing output signatures (old guest?)")
        result["output"] = out[0]
    if not with_trace:
        return result, rows, []
    # UART can split a hardware line even in the middle of a field.
    text = re.sub(r"\[RN-(?:ROW|OUTPUT|DIAG)\] [^\r\n]*\r?\n", "", text)
    accepted, done = {}, {}
    for line in re.findall(r"\[EVU-CMD\] [^\r\n]+", text):
        d = fields(line)
        if d["event"] not in ("accept", "complete"):
            continue
        key = int(d["ticket"])
        dest = accepted if d["event"] == "accept" else done
        require(key not in dest, "duplicate command ticket")
        dest[key] = d
    require(accepted and accepted.keys() == done.keys(), "missing trace or incomplete command")
    stream = []
    for key, d in sorted(accepted.items()):
        a, c = int(d["t"]), int(done[key]["t"])
        require(d["pc"] == done[key]["pc"] and c >= a, "command identity/time mismatch")
        stream.append((int(d["opcode"]), a, c))
    return result, rows, stream


def groups(mode, stream):
    def ops(cmds):
        return [x[0] for x in cmds]

    if mode in "BQ":
        require(ops(stream[:13]) == [16] * 7 + [81] * 6, "unexpected full-tile setup")
        stream = stream[13:]
    if mode == "L":
        require(ops(stream) == [16] * 16, "unexpected load probe sequence")
        yield (stream[-1][1] - stream[0][1],)
        return
    elif mode == "R":
        codes = ops(stream)
        require(codes[:17] == [16] * 17 and codes[17:18] == [50] and
                codes.count(50) == codes.count(55) == 16 and codes.count(62) == 15 and
                len(codes) == 64, "unexpected resident probe sequence")
        yield (max(x[2] for x in stream if x[0] == 55) - stream[17][1],)
        return
    else:
        for _ in range(8):
            store = [i for i, x in enumerate(stream) if x[0] == 17]
            require(len(store) >= 9, "missing output stores")
            end = store[8] + 1
            batch, stream = stream[:end], stream[end:]
            codes = ops(batch)
            require(codes[:3] == [16] * 3 and codes[-2:] == [17, 17] and
                    codes.count(118) == (12 if mode == "B" else 16) and codes.count(119) == 64 and
                    codes.count(55) == 16 and codes.count(68) == (16 if mode == "B" else 0),
                    "unexpected full-tile schedule")
            loads = [x for x in batch if x[0] == 16][1:17]  # Exclude gamma and both E0 constants.
            start = loads[0][1]
            sum_done = max(x[2] for x in batch if x[0] == 55)
            first_square = next(x[1] for x in batch if x[0] == 50)
            desc = [x for x in batch if x[0] == 102][-1]  # final FSGNJX, after any FABS
            finish = max(x[2] for x in batch[-2:])
            yield (loads[-1][1] - start, sum_done - start, sum_done - first_square,
                   desc[2] - start, finish - start, finish - desc[2])
    require(not stream, "unexpected trailing commands")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--no-trace", action="store_true",
                        help="validate difftest and report guest timings only")
    args = parser.parse_args()
    results = {}
    for mode in "LRBQ":
        try:
            result, rows, stream = read_log(args.directory / (mode + ".log"), mode, not args.no_trace)
            samples = [] if args.no_trace else list(groups(mode, stream))
        except (KeyError, ValueError, OSError) as exc:
            parser.exit(1, f"{mode}: {exc}\n")
        results[mode] = result
        if args.no_trace:
            if rows:
                print(f"{mode}: group drained_cycles")
                for r in rows:
                    print(r["group"], r["cycles"])
            continue
        columns = {"L": "first_to_last_load_accept", "R": "first_square_to_last_sum_done"}.get(
            mode, "load_span row_stage square_to_sum desc_from_row0 last_store_from_row0 desc_to_last_store")
        print(f"{mode}: group {columns}" + (" drained_cycles" if rows else ""))
        for i, sample in enumerate(samples):
            print(i, *sample, *([rows[i]["cycles"]] if rows else []))
    if len({r["inputs"] for r in results.values()}) != 1 or results["B"]["flags"] != results["Q"]["flags"]:
        parser.exit(1, "different input addresses or B/Q flags\n")
    if results["B"]["output"] != results["Q"]["output"]:
        parser.exit(1, "different B/Q output signatures\n")
    b, q = (int(results[m]["cycles"]) for m in "BQ")
    print(f"tile B={b} Q={q} Q-B={q-b:+d} cycles; flags={results['B']['flags']}")


if __name__ == "__main__":
    main()
