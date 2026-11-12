#!/usr/bin/env bash
set -euo pipefail

if [[ $# -gt 1 || ( $# -eq 1 && $1 != --check ) ]]; then
  printf 'Usage: bash %s [--check]\n' "$0" >&2
  exit 2
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repository_root=$(cd -- "$script_dir/../../.." && pwd)
dependency="$repository_root/difftest"
patch="$script_dir/../patches/difftest-pure-vector.patch"
expected=0fdb354a5188cefbb17565a2c5abdf8a58f39b8b

if [[ ! -f "$dependency/.git" && ! -d "$dependency/.git" ]]; then
  printf 'Initialize the pinned difftest submodule before applying the EVU patch.\n' >&2
  exit 1
fi
actual=$(git -C "$dependency" rev-parse HEAD)
if [[ "$actual" != "$expected" ]]; then
  printf 'Expected difftest %s, found %s; no files were changed.\n' "$expected" "$actual" >&2
  exit 1
fi

if git -C "$dependency" apply --reverse --check "$patch" >/dev/null 2>&1; then
  printf 'Pure-vector EVU difftest patch is already present.\n'
elif git -C "$dependency" apply --check "$patch"; then
  if [[ ${1:-} == --check ]]; then
    printf 'Pure-vector EVU difftest patch is applicable but has not been applied.\n' >&2
    exit 1
  fi
  git -C "$dependency" apply "$patch"
  printf 'Applied the pure-vector EVU difftest patch.\n'
else
  printf 'Difftest sources conflict with the EVU patch; existing edits were preserved.\n' >&2
  exit 1
fi

if [[ ${1:-} != --check ]]; then
  # The generator JAR packages git-listed sources. Include the two new files
  # without making a commit or staging their contents.
  git -C "$dependency" add --intent-to-add -- \
    src/test/csrc/difftest/checkers/evu.cpp src/test/scala/EvuInterfaceCheck.scala
fi
