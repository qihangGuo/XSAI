#!/usr/bin/env bash
# External CVT decode/backend regression and standalone EVU RTL export.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/test-evu-cvt.sh [test|no-forward|rtl]
  test        Run external decode and full CVT backend tests (default).
  no-forward  Run CVT backend tests with short/extended forwarding disabled.
  rtl         Emit standalone EVU SystemVerilog into build/evu-cvt/rtl.

Environment:
  EVU_CVT_JAVA_HOME       JDK installation (requires Java 21).
  EVU_CVT_MILL            Mill executable (requires Mill 0.12.3).
  EVU_CVT_VERILATOR_HOME  Verilator installation (requires Verilator 5.024).
  EVU_CVT_RESULTS_DIR    Log directory (default: build/evu-cvt/results).
  EVU_CVT_RTL_DIR        RTL directory (default: build/evu-cvt/rtl).

The script uses the current checkout and submodules. It never updates them.
EOF
}

die() { printf 'EVU_CVT_ERROR: %s\n' "$*" >&2; exit 2; }

evu_cvt_mode="${1:-test}"
if [[ "$evu_cvt_mode" == -h || "$evu_cvt_mode" == --help ]]; then usage; exit 0; fi
[[ $# -le 1 ]] || { usage >&2; exit 2; }
case "$evu_cvt_mode" in test|no-forward|rtl) ;; *) usage >&2; exit 2 ;; esac

evu_cvt_repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$evu_cvt_repo"
if [[ -n "${EVU_CVT_JAVA_HOME:-}" ]]; then
  export JAVA_HOME="$EVU_CVT_JAVA_HOME"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
if [[ -n "${EVU_CVT_VERILATOR_HOME:-}" ]]; then
  export PATH="$EVU_CVT_VERILATOR_HOME/bin:$PATH"
  unset VERILATOR_ROOT
fi
# Chisel's decoder minimizer is shipped with the pinned YunSuan tree.
export PATH="$evu_cvt_repo/yunsuan/src/main/resources:$PATH"
command -v espresso >/dev/null || die 'espresso is missing from the pinned YunSuan resources.'
evu_cvt_mill="${EVU_CVT_MILL:-mill}"
command -v java >/dev/null || die 'java is missing; set EVU_CVT_JAVA_HOME.'
command -v "$evu_cvt_mill" >/dev/null || die 'Mill is missing; set EVU_CVT_MILL.'
command -v verilator >/dev/null || die 'verilator is missing; set EVU_CVT_VERILATOR_HOME.'

evu_cvt_java_version="$(java -version 2>&1)"
evu_cvt_java_major="$(sed -nE 's/.*version "([0-9]+)[^" ]*".*/\1/p' <<< "$evu_cvt_java_version" | head -n 1)"
[[ "$evu_cvt_java_major" == 21 ]] || die "Java 21 is required; detected: $evu_cvt_java_version"
evu_cvt_mill_version="$("$evu_cvt_mill" --version 2>&1)"
[[ "$evu_cvt_mill_version" =~ Mill[[:space:]]+Build[[:space:]]+Tool[[:space:]]+version[[:space:]]+0\.12\.3([^0-9.]|$) ]] ||
  die "Mill 0.12.3 is required; detected: $evu_cvt_mill_version"
evu_cvt_verilator_version="$(verilator --version)"
[[ "$evu_cvt_verilator_version" =~ ^Verilator[[:space:]]+5\.024([[:space:]]|$) ]] ||
  die "Verilator 5.024 is required; detected: $evu_cvt_verilator_version"

for evu_cvt_required in \
  rocket-chip/common.sc rocket-chip/cde/common.sc rocket-chip/hardfloat/common.sc \
  utility/src/main/scala XSAICache/common.sc XSAICache/OpenNCB/src/main/scala \
  XSAICache/ZhuJiang/src/main/scala XSAICache/ZhuJiang/xs-utils/src/main/scala \
  CUTE/common.sc yunsuan/src/main/scala/yunsuan/vector/cvt/GenericConvert.scala; do
  [[ -e "$evu_cvt_required" ]] || die "Missing dependency $evu_cvt_required; see software/evu/docs/cvt-extension/REPRODUCE.md."
done
if [[ -n "${LOWP_CORE_INJECT_FAULT:-}${BF16_CVT_INJECT_FAULT:-}${NV_PAYLOAD_INJECT_FAULT:-}" ]]; then
  die 'Numeric fault injection is set; run an explicitly targeted negative test.'
fi

evu_cvt_results="${EVU_CVT_RESULTS_DIR:-build/evu-cvt/results}"
mkdir -p "$evu_cvt_results"
evu_cvt_stamp="$(date -u +%Y%m%dT%H%M%SZ)-$$"
evu_cvt_prefix="$evu_cvt_results/$evu_cvt_mode-$evu_cvt_stamp"
evu_cvt_args=(-i -j 1)
case "$evu_cvt_mode" in
  test)
    unset EVU_CVT_DISABLE_FORWARDING
    evu_cvt_args+=(evuCheck.test.testOnly evu.EvuLowPrecisionDecodeSpec evu.EvuCvtIntegrationSpec evu.EvuPackConvertSpec evu.EvuConfigRulesSpec)
    ;;
  no-forward)
    export EVU_CVT_DISABLE_FORWARDING=1
    evu_cvt_args+=(evuCheck.test.testOnly evu.EvuCvtIntegrationSpec)
    ;;
  rtl)
    evu_cvt_args+=(evuCheck.runMain xiangshan.evu.area.EvuAreaMain --target-dir "${EVU_CVT_RTL_DIR:-build/evu-cvt/rtl}")
    ;;
esac

{
  printf 'utc=%s\nmode=%s\nrepo=%s\n' "$evu_cvt_stamp" "$evu_cvt_mode" "$evu_cvt_repo"
  printf '%s\n%s\n%s\n' "$evu_cvt_java_version" "$evu_cvt_mill_version" "$evu_cvt_verilator_version"
  printf 'command='; printf '%q ' "$evu_cvt_mill" "${evu_cvt_args[@]}"; printf '\n'
  printf 'EVU_CVT_DISABLE_FORWARDING=%s\n' "${EVU_CVT_DISABLE_FORWARDING:-0}"
  git rev-parse HEAD
  git status --short
  git submodule status rocket-chip utility XSAICache CUTE yunsuan
  git -C rocket-chip submodule status cde hardfloat
  git -C XSAICache submodule status OpenNCB ZhuJiang
  printf 'source_sha256\n'
  git ls-files -co --exclude-standard -z src/main/scala/xiangshan/evu src/test/scala/evu \
    src/main/scala/xiangshan/backend/decode/EvuDecoder.scala software/evu/docs/cvt-extension \
    software/evu/include/evu_intrinsics.h software/evu/include/evu_csr.h build.sc .mill-version |
    sort -zu | xargs -0 sha256sum
  git -C yunsuan ls-files -co --exclude-standard -z src/main/scala/yunsuan/vector/cvt src/main/scala/yunsuan/vector/VectorConvert |
    sort -zu | while IFS= read -r -d '' evu_cvt_source; do sha256sum "yunsuan/$evu_cvt_source"; done
} > "$evu_cvt_prefix.manifest.txt"
printf 'EVU CVT %s: log=%s.log manifest=%s.manifest.txt\n' "$evu_cvt_mode" "$evu_cvt_prefix" "$evu_cvt_prefix"
set +e
"$evu_cvt_mill" "${evu_cvt_args[@]}" 2>&1 | tee "$evu_cvt_prefix.log"
evu_cvt_status="${PIPESTATUS[0]}"
set -e
# SRAMTemplate caches Chisel Definition objects within one test JVM. Elaborating
# the second full backend in that JVM refers to a definition from the first one.
# Start PACK-slot in a fresh Mill test invocation, preserving both logs/statuses.
if [[ "$evu_cvt_status" == 0 && "$evu_cvt_mode" == test ]]; then
  printf 'additional_command=%q -i -j 1 evuCheck.test.testOnly evu.EvuPackSlotSpec\n' "$evu_cvt_mill" >> "$evu_cvt_prefix.manifest.txt"
  set +e
  "$evu_cvt_mill" -i -j 1 evuCheck.test.testOnly evu.EvuPackSlotSpec 2>&1 | tee -a "$evu_cvt_prefix.log"
  evu_cvt_status="${PIPESTATUS[0]}"
  set -e
fi
printf '%s\n' "$evu_cvt_status" > "$evu_cvt_prefix.exitcode"
if [[ "$evu_cvt_status" == 0 && "$evu_cvt_mode" != rtl ]]; then
  if ! grep -Eq 'EVU_CVT_INTEGRATION_PASS operations=18 .*byteComparisons=[1-9][0-9]* .*memoryRequests=[1-9][0-9]*' "$evu_cvt_prefix.log"; then
    printf 'EVU_CVT_ERROR: Mill exited successfully without the active integration checker marker.\n' >&2
    printf '3\n' > "$evu_cvt_prefix.exitcode"
    exit 3
  fi
fi
exit "$evu_cvt_status"
