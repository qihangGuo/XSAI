# Pure Long-Vector Migration

## Scope and Constraints

Remove EVU FP quantization, matrix dequantization, Launch, direct CUTE ports,
bridge, dedicated matrix stores, and their reference-model/test support.
Preserve VLEN=4096, DLEN/MLEN=512, 16 KiB 1RW-banked VRF, ordinary arithmetic,
EXP2/reductions, four memory commands, translation, hazards, flush and real Ack
completion. Preserve independent CUTE/AME computation and ordinary EVU checking.
No local Scala/Mill build, RTL generation, emulator execution, or binary build.
Do not overwrite unrelated working-tree changes. Do not commit or push.

## Design Rationale

Dedicated matrix/vector coupling is no longer part of the programming model.
Retired encodings 0x18, 0x50 and 0x5e must trap, including at VL=0. Ordinary
instructions keep their encodings. CSR ABI 2's 13-word snapshot remains intact;
clear the quantization capability rather than claiming removed support.
The memory tag allocator must still drain physical traffic across flush.
The CPU AMU transport is shared infrastructure, not a removable quantization unit.

## Phase 1: RTL and ISA

Remove exclusive modules and special VRF request ports. Simplify top/backend,
scoreboard, memory ownership, area wrappers and CPU decode/preflight connections.
Keep ordinary illegal-command completions, ordered completion and drain checking.
Local check: `git diff --check` and source dependency audits.
Server checks (run each invocation separately to avoid cross-elaboration SRAM
definition caching):

```sh
mill -i evuCheck.test.testOnly evu.EvuControlSequencerSpec evu.EvuControlBarrierSpec \
  evu.EvuCommandScoreboardSpec evu.EvuMemoryTagRouterSpec evu.EvuDispatchAdapterSpec
mill -i evuCheck.test.testOnly evu.EvuRetiredOpcodeSpec
mill -i evuCheck.test.testOnly evu.EvuRmwStoreCoalescingSpec \
  evu.EvuHbl2LineAdapterSpec evu.EvuVmuLineCoalescerSpec
mill -i evuCheck.test.testOnly evu.EvuAreaTopSpec
mill -i evuCheck.test.testOnly evu.EvuCompletionLoopSpec
mill -i evuMmuCheck.test.testOnly xiangshan.evucheck.EvuRobOrderingSpec \
  xiangshan.evucheck.EvuAmuCompletionSpec xiangshan.evucheck.EvuCompletionStatusSpec \
  xiangshan.evucheck.EvuConfigCSRSpec xiangshan.evucheck.EvuExeUnitElaborationSpec
```

PASS: legal commands complete, rejected commands have no data side effects,
flush drains accepted requests and all tag credits recover. FAIL: assertion,
timeout, unsupported opcode accepted, or ordinary SRAM geometry changes.

## Phase 2: Reference, Difftest and Software

Remove fused NEMU matrix model and AME hooks, retain standalone matrix semantics.
Remove only matrix/cute events from difftest; preserve ordinary EVU VRF/CSR/store,
completion, error and drain validation. Retire old fused FlashAttention tests,
update default target and archive architecture-specific documentation.
Local: `python3 -m unittest discover -s software/evu/scripts -p 'test_*.py'`.
Server (after rebuilding the reference with `riscv64-xs-evu-ref_defconfig`):

```sh
make -C ../NEMU/tools/evu-ref-test NEMU_HOME="$PWD/../NEMU"
../NEMU/tools/evu-ref-test/evu_compute_ref_test \
  ../NEMU/build/riscv64-nemu-interpreter-so
bash software/evu/scripts/check_softmax_ref.sh ../NEMU
```

PASS: all ordinary reference and script tests pass and retired opcode negatives
trap without state changes. Server builds must use the matching reference ABI.

## Phase 3: Official Dependencies

Baseline queried 2026-11-10: OpenXiangShan/XSAI master
63c767f1be1d874ce7de420318017be35aefcac5. Pin its hardware dependencies, including
their recursive gitlinks, and apply necessary parent integration changes.
Rebase only the minimal ordinary EVU difftest extension onto its pinned
0fdb354a5188cefbb17565a2c5abdf8a58f39b8b. Preserve prior dirty submodule work
before changing revisions. No changes to cache internals for EVU functionality.
Local: `git diff --check`, submodule revision and interface audits.
Run `bash software/evu/scripts/apply_pure_vector_difftest.sh` after initializing
the official dependency. Its checked-in patch preserves the ordinary EVU
extension without a private difftest gitlink; `--check` verifies it read-only.
The independent matrix checker now uses CRC32C. The corresponding NEMU hash
implementation was ported from official NEMU `6f7be63b`; leaving the older hash
in NEMU would cause matrix mismatches unrelated to EVU execution.
Server: `make emu CONFIG=DefaultExternalVectorMatrixConfig ACCELERATOR_CDC_ENABLE=0 EMU_THREADS=8 EMU_TRACE=fst -j16`.
PASS: regenerated simulator and reference build; no stale custom CUTE interfaces.

### Pinned Official Revisions

These are the gitlinks of the recorded November 10 snapshot, not a claim that
every upstream repository's branch tip has been integrated independently.

| Dependency | Commit |
| --- | --- |
| CUTE | `56c70036671471369dedfeebf06e27f05473dc40` |
| ChiselAIA | `2e8b9b062f74e47f58e91ef5d6d6a584fc235e81` |
| ChiselIOPMP | `09def1a7efa4707a74b7f31fe0a1c254b0940b78` |
| XSAICache | `e8db9a6448c9684ab95b3073fcb7ff2aac13e030` |
| difftest | `0fdb354a5188cefbb17565a2c5abdf8a58f39b8b` + ordinary EVU patch |
| fudian | `e1bd4695ca7beb36a5ce7357e9527ad9e95b9ec1` |
| ready-to-run | `c16bdc74bfbfffd6c38179aa4c1376db3cbace1e` |
| rocket-chip | `bb5d63cbddcc40262b2fd2fe6cf5ecd287094488` |
| utility | `b3afd2908ec06ca58cc5f4ea1d3989ad3a4a5b03` |
| yunsuan | `f7cced213ef61f6f2a960d95582befabc6b5282c` |

## Final Verification (Server Only)

Build each software test with `make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=<test>`.
Run vector_add_evu_test, exp2_evu_test, csr_context_evu_test,
virtual_memory_evu_test, vector_memory_path_evu_test and online_softmax_evu_test
with `./build/emu --diff ../NEMU/build/riscv64-nemu-interpreter-so -C 100000000 -i software/evu/build/<test>-riscv64-xs.bin`.
Require GOOD TRAP, zero outstanding jobs and no difftest/assertion failures.
Use online softmax CHECK=1 and both first modes, plus FENCE interval counters.
Run independent CUTE matrix smoke/regression and the project's ordinary Linux
regression (10 minutes) before treating the dependency upgrade as validated.
Area/performance conclusions require fresh server measurements, not static claims.
