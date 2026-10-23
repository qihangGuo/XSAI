#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 || ( $# -eq 2 && $2 != --rebuild ) ]]; then
  printf 'Usage: bash %s NEMU_REPO [--rebuild]\n' "$0" >&2
  exit 2
fi

xsai_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)
nemu_repo=$(cd -- "$1" && pwd)
export NEMU_HOME="$nemu_repo"

if [[ ${2:-} == --rebuild ]]; then
  make -C "$nemu_repo" git_commit= riscv64-xs-evu-ref_defconfig
  # Interpreter hooks live in headers included by cpu-exec.c. Rebuild the
  # entire reference so stale interpreter objects cannot omit those hooks.
  make -C "$nemu_repo" git_commit= clean
  make -C "$nemu_repo" git_commit= -j8
fi

reference="$nemu_repo/build/riscv64-nemu-interpreter-so"
test -f "$reference"
mkdir -p "$xsai_repo/out"
cc -std=gnu11 -O2 -D__ISA__=riscv64 \
  -I "$nemu_repo/include" -I "$nemu_repo/src/isa/riscv64/include" \
  -I "$nemu_repo/src/isa/riscv64/instr" \
  "$xsai_repo/software/evu/host/evu_matrix_ref_test.c" \
  -o "$xsai_repo/out/evu-matrix-ref-test" -ldl
sha256sum "$reference"
"$xsai_repo/out/evu-matrix-ref-test" "$reference"
printf 'EVU matrix reference PASS: %s\n' "$reference"
