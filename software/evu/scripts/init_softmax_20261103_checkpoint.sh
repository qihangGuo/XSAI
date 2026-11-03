#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_root="$(git -C "$script_dir" rev-parse --show-toplevel)"

check_pin() {
  local path="$1" expected="$2" actual
  actual="$(git -C "$repository_root" rev-parse "HEAD:$path")"
  if [[ "$actual" != "$expected" ]]; then
    printf 'Unexpected %s revision: %s; this helper is for the 2026-11-03 softmax checkpoint.\n' \
      "$path" "$actual" >&2
    exit 1
  fi
}

check_pin CUTE b62bd4e1024cbcd29b0b55ec5687efa2ff52dad6
check_pin XSAICache f8133554705899560be5f33a701a0ce4b5aae791
check_pin utility 3afcdfc17f6551b12250a283185dce2a8c661287
check_pin difftest 9206c5ee3d2a3da049c800b1e2a293e552117094

# Historical dependency objects are advertised by tags in these personal mirrors.
# Keep the tracked .gitmodules URLs and all recorded gitlink SHAs unchanged.
git -C "$repository_root" config submodule.CUTE.url https://github.com/qihangGuo/CUTE.git
git -C "$repository_root" config submodule.XSAICache.url https://github.com/qihangGuo/XSAI.git
git -C "$repository_root" config submodule.utility.url https://github.com/qihangGuo/XSAI.git
git -C "$repository_root" submodule update --init --recursive
