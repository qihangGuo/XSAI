#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(git -C "$script_dir" rev-parse --show-toplevel)"

check_pin() {
  local path="$1" expected="$2" actual
  actual="$(git -C "$repository_root" rev-parse "HEAD:$path")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'Unexpected %s revision: %s; use the dependency helper shipped with the selected checkpoint.\n' \
      "$path" "$actual" >&2
    exit 1
  fi
}

check_pin CUTE b62bd4e1024cbcd29b0b55ec5687efa2ff52dad6
check_pin XSAICache 1186ab336ad130767f6e86fd6afc7cd1d24d1098
check_pin utility b3afd2908ec06ca58cc5f4ea1d3989ad3a4a5b03
check_pin difftest 9206c5ee3d2a3da049c800b1e2a293e552117094

# Only CUTE needs the personal mirror. Restore the official dependency URLs,
# including in existing clones initialized with the older checkpoint helper.
git -C "$repository_root" config submodule.CUTE.url https://github.com/qihangGuo/CUTE.git
git -C "$repository_root" submodule sync -- XSAICache utility
git -C "$repository_root" submodule update --init

printf 'Top-level submodules are initialized. Run `make init` to fetch the nested dependencies required by this XSAI checkout.\n'
