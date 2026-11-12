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
make -C "$nemu_repo/tools/evu-ref-test" NEMU_HOME="$nemu_repo"
"$nemu_repo/tools/evu-ref-test/evu_compute_ref_test" "$reference" --slide
cd -- "$xsai_repo"
export PATH="$xsai_repo/src/main/resources:$PATH"
export CCACHE_DISABLE="${CCACHE_DISABLE:-1}"
mill -i evuCheck.test.testOnly evu.EvuSlideDecodeSpec evu.EvuSlideSpec \
  evu.EvuDispatchAdapterSpec evu.EvuRetiredOpcodeSpec \
  evu.EvuControlSequencerSpec evu.EvuControlBarrierSpec
mill -i evuMmuCheck.test.testOnly xiangshan.evucheck.EvuRobOrderingSpec xiangshan.evucheck.EvuAmuCompletionSpec \
  xiangshan.evucheck.EvuCompletionStatusSpec
printf 'EVU slide and fence-only reference/RTL checks PASS\n'
