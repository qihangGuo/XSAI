#!/usr/bin/env bash
set -euo pipefail

if [[ $# -gt 1 ]]; then
  printf 'Usage: bash %s [NEMU_REPO]\n' "$0" >&2
  exit 2
fi

xsai_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
nemu_repo=$(cd -- "${1:-$xsai_repo/../NEMU_evu}" && pwd)
reference="$nemu_repo/build/riscv64-nemu-interpreter-so"
test -f "$reference"
output="$xsai_repo/out/evu-rvv-math-difftest"
mkdir -p "$output"
make -C "$nemu_repo/tools/evu-ref-test" NEMU_HOME="$nemu_repo"
"$nemu_repo/tools/evu-ref-test/evu_compute_ref_test" "$reference" --rvv-math "$output/reference.trace"
cd -- "$xsai_repo"
export PATH="$xsai_repo/src/main/resources:$PATH"
export CCACHE_DISABLE="${CCACHE_DISABLE:-1}"
export EVU_RVV_MATH_TRACE="$output/reference.trace"
mill -i evuCheck.test.testOnly evu.EvuRvvMathDecodeSpec
mill -i evuCheck.test.testOnly evu.EvuRvvMathDifftestSpec
printf 'EVU RVV math reference/RTL checks PASS; trace: %s\n' "$EVU_RVV_MATH_TRACE"
