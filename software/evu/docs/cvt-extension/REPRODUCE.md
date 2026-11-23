# EVU CVT integration: reproduce the RTL checks

Run `scripts/test-evu-cvt.sh` from this checkout. It checks Java 21, Mill 0.12.3
and Verilator 5.024, records the source versions and hashes, then runs the
external decode and full backend CVT suites. The script keeps the current
submodule revisions; it does not initialize or update dependencies.

The YunSuan provider used during this integration is local commit
`7b4f591ab8d302d879a74125f569837fb6742f7f` (`feat/cvt-evu-provider`). It is not yet
published. Until the final XSAI commit pins a publicly available YunSuan commit,
a fresh public clone needs the local YunSuan repository as another local clone.

## Dependencies

`evuCheck` in the root `build.sc` compiles EVU independently of the full scalar
core. Its module dependencies are Rocket Chip, YunSuan and XSAICache. XSAICache
also uses root Utility, OpenNCB, and ZhuJiang including its checked-in `xs-utils`
sources. Mill reads `CUTE/common.sc` when loading the root build definition.
The following targeted initialization covers this graph:

```sh
git submodule update --init rocket-chip utility XSAICache CUTE
git -C rocket-chip submodule update --init cde hardfloat
git -C XSAICache submodule update --init OpenNCB ZhuJiang
```

Initialize these dependencies in a fresh checkout. Running a blanket
`git submodule update --init --recursive` on the active integration worktree
would reset the selected local YunSuan provider to the parent commit's gitlink.
Full SoC targets need their own additional dependencies.

For the unpublished YunSuan candidate, supply its local repository explicitly:

```sh
export EVU_CVT_YUNSUAN_SOURCE=/absolute/path/to/cvt-pr-prep/YunSuan
# Only in a fresh checkout where yunsuan has not yet been initialized:
git -c protocol.file.allow=always \
  -c submodule.yunsuan.url="$EVU_CVT_YUNSUAN_SOURCE" submodule update --init yunsuan
git -C yunsuan fetch "$EVU_CVT_YUNSUAN_SOURCE" feat/cvt-evu-provider
git -C yunsuan switch --detach 7b4f591ab8d302d879a74125f569837fb6742f7f
git -C yunsuan rev-parse HEAD
```

Once the provider branch is pushed to the author's fork, parallel draft review
can fetch the exact gitlink without changing the committed `.gitmodules`:

```sh
git config submodule.yunsuan.url https://github.com/mydu-00/YunSuan.git
git submodule update --init yunsuan
```

This command requires the fork branch to have been published. For an already
initialized submodule whose origin still points upstream, explicitly fetch it:

```sh
git -C yunsuan fetch https://github.com/mydu-00/YunSuan.git feat/cvt-evu-provider
git submodule update --init yunsuan
```

After the coordinated PRs publish and pin the provider, a fresh checkout uses
`git submodule update --init yunsuan` at the finalized XSAI revision instead.
The script manifest records the actual YunSuan HEAD and marks gitlink mismatch
with Git's normal `+` submodule status prefix.

The tested build uses Scala 2.13.15, Chisel 6.7.0 and chiseltest 6.0.0, resolved
by the root `build.sc`. Mill also resolves its compiler worker and FIRRTL tools;
first use needs access to the configured Maven repositories or a populated
Coursier cache. Verilator needs a working C++ compiler and Make. The script adds the pinned YunSuan `src/main/resources/espresso` decoder minimizer to PATH. For offline RTL export, set `CHISEL_FIRTOOL_PATH` to the directory containing firtool 1.62.1.

## Commands

Select installed tool locations without changing the repository:

```sh
export EVU_CVT_JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export EVU_CVT_MILL=/absolute/path/to/mill-0.12.3
export EVU_CVT_VERILATOR_HOME=/absolute/path/to/verilator-5.024

scripts/test-evu-cvt.sh
scripts/test-evu-cvt.sh no-forward
scripts/test-evu-cvt.sh rtl
```

The defaults also work when the required versions are already on `PATH`.
Each run writes a timestamped `.log`, `.exitcode` and `.manifest.txt` under
`build/evu-cvt/results`; set `EVU_CVT_RESULTS_DIR` to change that directory.
The manifest includes the effective command, tool versions, Git HEAD/status,
dependency revisions and SHA-256 hashes of EVU test/production sources and the
YunSuan conversion sources. Run one Mill build at a time in this worktree.

| Mode | Mill target | Configuration |
|---|---|---|
| `test` | `evuCheck.test.testOnly evu.EvuLowPrecisionDecodeSpec evu.EvuCvtIntegrationSpec evu.EvuPackConvertSpec evu.EvuConfigRulesSpec` | Forwarding enabled; decode, mixed PACK/CVT, PACK-slot and capability/configuration checks |
| `no-forward` | `evuCheck.test.testOnly evu.EvuCvtIntegrationSpec` | `EVU_CVT_DISABLE_FORWARDING=1` disables short/extended result forwarding |
| `rtl` | `evuCheck.runMain xiangshan.evu.area.EvuAreaMain --target-dir build/evu-cvt/rtl` | Default standalone EVU, VLEN4096/DLEN512/MLEN512, 8 lanes and 4 banks per lane |

After the main `test` invocation, the script runs
`evuCheck.test.testOnly evu.EvuPackSlotSpec` in a separate test process.
The SRAM library caches Chisel Definition objects within a JVM; elaborating
two complete banked backends in one process can reference the first circuit's
definitions. Separate invocations preserve the original RTL and test behavior.
Both invocations must pass; the script records their combined status.

`rtl` emits split SystemVerilog and `area_config.json`; set `EVU_CVT_RTL_DIR`
to choose another output directory. This elaborates the standalone EVU design.
It does not run a workload or establish synthesis timing/area closure.

## Acceptance and scope

The test path is external custom0 instruction → `EvuDispatchAdapter` →
`EvuVectorBackend` → Saturn issue queues, production VRF and conversion FUs →
EVU store requests. A byte-addressed memory model supplies backpressure and
tagged responses. Stored register bytes and active fflags are compared with
independent BigInt numerical models in `src/test/scala/evu/cvtref`.

Coverage includes all 18 conversions, VL=0/1/15/16/17/127/128, packed FP4/BF16
sources and destinations, tail preservation, odd FP4 tails, RNE and special
values, supplied UE8M0 boundaries, scale RAW/WAR, mixed one/two-cycle conversion
dependencies, completion backpressure, illegal commands at VL0 and flush
recovery. `EVU_CVT_BANK` reports equal-input compute windows for two scale
register placements under always-ready memory.

A passing run must have a zero exit code, passing ScalaTest suites and
`EVU_CVT_INTEGRATION_PASS operations=18` with nonzero `byteComparisons` and
`memoryRequests`. The script rejects a successful Mill process that lacks this
active-checker marker. Read the actual counters in the log; counts are not
inferred from elaboration or process exit alone.

The shell observes backend completion. It does not include the scalar ROB,
NEMU or whole-SoC execution. The library's separate five-rounding-mode numerical
suite remains in YunSuan `scripts/test-cvt.sh`; this EVU interface fixes RNE.

Static software checks can be run independently:

```sh
python3 software/evu/docs/cvt-extension/check_isa.py
python3 software/evu/docs/cvt-extension/check_asm.py
```

The assembler check requires Clang with RISC-V support and validates emitted
instruction words. Dynamic execution is established by the RTL suites above.

## Recorded candidate results (2026-11-23)

VLEN4096/DLEN512: each forwarding setting passed 193 scenarios, 1,068 external commands and 107,008 byte comparisons. The tests include 4 targeted FP4 RAW/WAR scenarios and an eight-slot PACK/MX-encode/MX-decode/BF16 coexistence scenario. PACK-slot ordering and shared-provider result/flags/tag tests pass independently. Independent YunSuan regression passed 26 tests. VRF mapping, byte masking, physical write completion, short forwarding and configuration passed 25 tests. The latest upstream ownerless-reduction and seed-forwarding regression passed 1 test with Verilator. Production EvuAreaTop export and Verilator lint passed with -Wno-fatal; width and upstream warnings remain in the log. These are numerical and backend results; no technology-specific frequency/area result is claimed.

For the upstream ownerless-reduction test, `EVU_TEST_VERILATOR=1` selects Verilator; its default remains Treadle. This avoids slow interpreter compilation of the enlarged default FU graph.
