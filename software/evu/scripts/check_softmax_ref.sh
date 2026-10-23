#!/usr/bin/env bash
set -euo pipefail

if [[ $# -gt 1 ]]; then
  printf 'Usage: bash %s [NEMU_REPO]\n' "$0" >&2
  exit 2
fi

xsai_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
nemu_repo=$(cd -- "${1:-$xsai_repo/../NEMU}" && pwd)
reference="$nemu_repo/build/riscv64-nemu-interpreter-so"
test -f "$reference"
output="$xsai_repo/out/evu-softmax-difftest"
mkdir -p "$output"

make -C "$nemu_repo/tools/evu-ref-test" NEMU_HOME="$nemu_repo"
host_test="$nemu_repo/tools/evu-ref-test/evu_compute_ref_test"
"$host_test" "$reference" "$output/reference.trace"

cd -- "$xsai_repo"
# Verilator PCH artifacts in Mill's temporary test directories must not be
# reused from a ccache entry that refers to an already removed directory.
export CCACHE_DISABLE="${CCACHE_DISABLE:-1}"
export PATH="$xsai_repo/src/main/resources:$PATH"
export EVU_SOFTMAX_REFERENCE_TRACE="$output/reference.trace"
export EVU_SOFTMAX_DUT_TRACE="$output/rtl.trace"
# The SRAM utility caches Chisel Definitions across elaborations. Run the
# two equal-sized backend harnesses in separate test JVMs.
mill -i evuCheck.test.testOnly evu.EvuSoftmaxDifftestSpec
mill -i evuCheck.test.testOnly evu.EvuSaturnReductionSpec evu.EvuFpFlagsSpec
"$host_test" "$reference" --replay "$output/rtl.trace"
printf 'EVU softmax reference/RTL/ABI checks PASS; traces: %s\n' "$output"
