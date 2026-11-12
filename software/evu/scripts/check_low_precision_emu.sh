#!/usr/bin/env bash
set -euo pipefail

: "${AM_HOME:?Set AM_HOME to the server nexus-am checkout}"
xsai_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
nemu_repo=$(cd -- "${1:-$xsai_repo/../NEMU_evu}" && pwd)
reference="$nemu_repo/build/riscv64-nemu-interpreter-so"
emulator=${EVU_EMU:-$xsai_repo/build/emu}
output=${EVU_LOW_PRECISION_OUTPUT:-$xsai_repo/out/evu-low-precision-emu}
mkdir -p "$output"
output=$(cd -- "$output" && pwd)
export NOOP_HOME="$xsai_repo"
export NEMU_HOME="$nemu_repo"
test -f "$reference"
test -x "$emulator"
cd -- "$xsai_repo"

run_guest() {
  local name=$1 label=$2
  shift 2
  make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME="$name" BINARY="$output/$label" "$@" \
    2>&1 | tee "$output/$label.build.log"
  "$emulator" --diff "$reference" -C 1000000000 -i "$output/$label.bin" -- +evu_mem_trace=1 \
    2>&1 | tee "$output/$label.log"
  grep -q 'HIT GOOD TRAP' "$output/$label.log"
  if grep -Eq 'HIT BAD TRAP|\[RN\] FAIL|\[LOWP\] FAIL|Assertion.*fail|ABORT' "$output/$label.log"; then return 1; fi
}

run_guest low_precision_evu_test lowp
grep -q '\[LOWP\] PASS' "$output/lowp.log"
run_guest vector_add_evu_test vector-add
run_guest exp2_evu_test exp2
run_guest csr_context_evu_test csr
run_guest online_softmax_evu_test softmax-check EVU_SOFTMAX_CHECK=1
run_guest online_softmax_evu_test softmax
python3 software/evu/scripts/summarize_softmax_fences.py "$output/softmax.log" > "$output/softmax.txt"
run_guest rmsnorm_nvfp4_evu_test rms-only EVU_RMS_ONLY=1 EVU_RMS_VERBOSE=1
run_guest rmsnorm_nvfp4_evu_test nvfp4 EVU_RMS_VERBOSE=1
python3 software/evu/scripts/summarize_rmsnorm_nvfp4.py "$output/rms-only.log" > "$output/rms-only.csv"
python3 software/evu/scripts/summarize_rmsnorm_nvfp4.py "$output/nvfp4.log" > "$output/nvfp4.csv"
python3 software/evu/scripts/summarize_rmsnorm_counters.py "$output/nvfp4.log" > "$output/nvfp4-counters.csv"
if [[ -n ${EVU_BEFORE_EMU:-} ]]; then
  : "${EVU_BEFORE_REF:?The before emulator requires its matching saved NEMU reference}"
  for label in softmax rms-only; do
    "$EVU_BEFORE_EMU" --diff "$EVU_BEFORE_REF" -C 1000000000 -i "$output/$label.bin" -- +evu_mem_trace=1 \
      2>&1 | tee "$output/$label.before.log"
    grep -q 'HIT GOOD TRAP' "$output/$label.before.log"
  done
  python3 software/evu/scripts/summarize_softmax_fences.py "$output/softmax.before.log" > "$output/softmax.before.txt"
  python3 software/evu/scripts/compare_rmsnorm_runs.py "$output/rms-only.before.log" "$output/rms-only.log" \
    > "$output/rms-comparison.csv"
fi
printf 'Server acceptance PASS; logs and CSV: %s\n' "$output"
