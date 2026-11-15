#!/usr/bin/env bash
set -euo pipefail

# Run on the server with one unchanged EMU/NEMU pair for both guest variants.
: "${AM_HOME:?Set AM_HOME to the server nexus-am checkout}"
xsai_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
nemu_repo=$(cd -- "${1:-$xsai_repo/../NEMU_evu}" && pwd)
reference="$nemu_repo/build/riscv64-nemu-interpreter-so"
emulator=${EVU_EMU:-$xsai_repo/build/emu}
output=${EVU_RMS_SOFTWARE_OUTPUT:-$xsai_repo/out/rmsnorm-software}
mkdir -p "$output"
output=$(cd -- "$output" && pwd)
export NOOP_HOME="$xsai_repo"
export NEMU_HOME="$nemu_repo"
test -f "$reference"
test -x "$emulator"
cd -- "$xsai_repo"
sha256sum "$emulator" "$reference" > "$output/artifacts.sha256"

for banked in 0 1; do
  label="bank$banked"
  make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=rmsnorm_nvfp4_evu_test \
    BINARY="$output/$label" EVU_RMS_LANE_SUM=1 EVU_RMS_QUANT_RENAME=1 \
    EVU_RMS_QUANT_BANKED="$banked" EVU_RMS_ONLY=0 EVU_RMS_VERBOSE=1 \
    EVU_RMS_SAMPLES="${EVU_RMS_SAMPLES:-1}" 2>&1 | tee "$output/$label.build.log"
  sha256sum "$output/$label.bin" >> "$output/artifacts.sha256"
  "$emulator" --diff "$reference" -C 1000000000 -i "$output/$label.bin" -- +evu_mem_trace=1 \
    2>&1 | tee "$output/$label.log"
  grep -Eq '\[DiffTest\]\[EVU\].*pending=0([[:space:]]|$)' "$output/$label.log"
  python3 software/evu/scripts/summarize_rmsnorm_nvfp4.py "$output/$label.log" > "$output/$label.csv"
  python3 software/evu/scripts/summarize_rmsnorm_counters.py "$output/$label.log" > "$output/$label-counters.csv"
done

python3 software/evu/scripts/compare_rmsnorm_runs.py "$output/bank0.log" "$output/bank1.log" \
  > "$output/comparison.csv"
printf 'RMSNorm software comparison complete: %s\n' "$output"
