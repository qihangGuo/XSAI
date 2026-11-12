#!/usr/bin/env bash
set -euo pipefail

xsai_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
nemu_repo=$(cd -- "${1:-$xsai_repo/../NEMU_evu}" && pwd)
reference="$nemu_repo/build/riscv64-nemu-interpreter-so"
test -f "$reference"
output="$xsai_repo/out/evu-low-precision-difftest"
mkdir -p "$output"
make -C "$nemu_repo/tools/evu-ref-test" NEMU_HOME="$nemu_repo"
"$nemu_repo/tools/evu-ref-test/evu_compute_ref_test" "$reference" --lowp "$output/reference.trace"
cd -- "$xsai_repo"
export PATH="$xsai_repo/src/main/resources:$PATH"
export CCACHE_DISABLE="${CCACHE_DISABLE:-1}"
export EVU_LOW_PRECISION_TRACE="$output/reference.trace"
mill -i evuCheck.test.testOnly evu.EvuLowPrecisionDecodeSpec
mill -i evuCheck.test.testOnly evu.EvuLowPrecisionDifftestSpec
