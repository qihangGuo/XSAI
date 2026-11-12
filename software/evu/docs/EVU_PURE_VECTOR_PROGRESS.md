# Pure Long-Vector Migration Progress

Updated November 11, 2026. Implementation is complete; server validation is not.

| Phase | Implementation | Local Validation | Server Validation |
| --- | --- | --- | --- |
| RTL/ISA removal | Complete | Source/interface audit, whitespace PASS | Scala compile failed; fix pending rebuild |
| NEMU/difftest/software | Complete | C syntax, 24 parser tests, 182 software schedules PASS | Not run |
| Official dependency integration | Complete | Gitlink and patch applicability checks PASS | Dependency Scala compilation passed; parent pending |
| Full regression/area/performance | Pending | Not applicable | Not run |

## Constraints

Local RTL generation, Java/Mill, emu and binary builds remain prohibited by
the user. Existing scheduling, ABI and correctness changes are preserved
unless exclusive to the removed fused path. No commit or push was performed.
No cycle count, area result or hardware-test PASS is claimed for this migration.

## Phase 1: RTL and ISA

### Changes Made

- Deleted FP quantization, matrix dequantization, root special sequencer,
  matrix store buffer, bridge and the unused elastic-stage helper.
- Removed dedicated VRF read ports, matrix ownership reservations, direct
  CUTE interfaces and matrix-only diagnostics/difftest producers.
- Converted the memory tag router to one VMU client, retaining physical tag
  ownership, flush drain and cancellation acknowledgements.
- Kept 512-bit compute/memory, VLEN=4096, 16 KiB VRF, 32 single-port 64x64
  SRAM banks, depth-four memory window, eight tags and ordinary arithmetic,
  EXP2, reductions, chaining, translation and real-Ack completion.
- Retired 0x18/0x50/0x5e decode to illegal instructions even at VL=0.
  EVCAP bit 25 reads zero; CSR ABI 2 and its 13-word snapshot are unchanged.
- Preserved shared CPU AMU retirement infrastructure and independent CUTE
  execution. Ordinary commands publish flags/errors individually; FENCE and
  drain no longer aggregate nonexistent matrix work.

### Test Results

Source/interface audits and `git diff --check`: PASS. Added or adapted RTL
tests cover retired encodings, command barriers, single-client tags, area
boundary and CPU completion ownership. RMW integration cases cover flush
before request, queued Get, outstanding Get, prepared Put, outstanding Put
and final Ack coincident with flush, plus response backpressure/tag recovery.
These Scala tests have not been compiled or executed locally.

### Issues and Resolution

The CUTE test wrapper still referenced five deleted vector-result/direct-write
ports after tile-level removal. A whole-`src` audit found and removed them;
the wrapper now matches the official interface. The tag router was not
bypassed because Saturn still needs cancellation Acks after a flush.

### First Server Build: November 11

The 10:14:21 +0800 build reached parent `xiangshan.compile` after compiling
YunSuan, ChiselAIA, difftest, ZhuJiang compatibility, XSAICache and CUTE.
It stopped before RTL generation with one Scala error at
`backend/exu/ExeUnit.scala:428`: `not found: value EnableExternalVector`.
The diagnostic guard referenced a field of `XSCoreParameters` as though
`HasXSParameter` exported a same-named method. It does not. The guard now
uses the existing `coreParams.EnableExternalVector` accessor. Functional
dispatch, drain, memory control and ISA are unchanged.

`EvuExeUnitElaborationSpec` now includes the real shared BJU3 unit with
difftest enabled and EVU both enabled/disabled, checking that the diagnostic
is emitted only in the EVU configuration. Local source/whitespace checks
only; the Scala regression and server rebuild are pending. This correction
does not require further NEMU or guest changes beyond the migration rebuild.

## Phase 2: Reference, Difftest and Software

### Changes Made

- NEMU: removed the fused matrix oracle and AME hooks, retained ordinary
  AME/EVU models, pending-job growth, per-command flags, VRF/store checking
  and physical merged-store ownership/guard-byte checking.
- Difftest: removed EVU matrix/CUTE events and required exports; retained
  Store/Completion/Drain/VRF/CSR events, post-scalar CSR checking and
  outstanding-work checks. Unsupported FAST/FORK/replay states are rejected.
- Retired fused FlashAttention/quantization guest and host test sources.
  Historical documents are under `archive/matrix-vector/`; default software
  is vector add. Six ordinary EVU applications and softmax helpers remain.
- Added a checked-in minimal difftest patch and idempotent application script
  so the parent can reference an official difftest commit reproducibly.

### Test Results

- Python unittest discovery: 24 tests PASS. Covers FENCE interval and command
  summaries, including ignoring retired matrix records.
- Source-driven softmax check: 182 schedules PASS with Clang syntax checks
  and an interpreted ideal-EXP2 numerical model. This is not RTL/NEMU execution.
- GCC `-fsyntax-only -Wall -Werror`: PASS for NEMU EVU model/compute,
  AME load-store, CRC32C implementation, compute-reference test source and
  `src/cpu/cpu-exec.c`, including the independent AME instruction helpers.
  No executable, shared library or guest binary was built.
- Shell syntax and difftest-patch application checks: PASS. Patch is reverse
  applicable to the current tree and forward applicable to the official base;
  its contents exactly match the complete current difftest source delta.
- Host reference executable, RTL trace comparison and guest regressions:
  not run; require matching rebuilt server artifacts.

## Phase 3: Official Dependencies

### Changes Made

Hardware gitlinks match official XSAI `63c767f1` (snapshot queried November 10,
2026), including the pinned recursive dependency versions. Exact revisions
are in `EVU_PURE_VECTOR_PLAN.md`. CUTE and XSAICache have no EVU-specific
source edits; difftest is official `0fdb354a` plus the recorded extension.

Parent compatibility changes tie off new YunSuan VCVT inputs, adapt matrix
store events and use one post-arbitration ordinary matrix control probe.
NEMU's independent matrix hash was ported from official `6f7be63b` to CRC32C,
matching the new CUTE/difftest. This is not a full parent-SoC/upstream merge.

### Test Results and Remaining Risks

Initialized submodule revisions have no `+` or `U` mismatch. ChiselIOPMP,
ready-to-run and some unused nested test dependencies remain uninitialized
locally; initialize the pinned dependencies on the build server. Scala/C++
integration compilation is still required. Existing generated headers and
RTL under `build/` predate this migration and are not valid validation inputs.

### Preserved Work

- CUTE stash: `pre-pure-vector-20261110-cute`.
- Difftest stash: `pure-vector-before-upstream-20261110`.
- Additional pre-migration patches are in `/tmp/evu-pure-vector-*.patch`.
- Gitlink changes are staged locally; no new checkpoint has been committed.

## Server Acceptance

Follow `EVU_PURE_VECTOR_PLAN.md` and the current software README. Rebuild
NEMU, emu and guest bins together. Require ordinary EVU microtests and
softmax correctness to pass, retired-opcode negatives to trap, independent
CUTE regression to pass, and the project's 10-minute Linux regression before
treating the dependency upgrade as validated. Compare identical software
options and FENCE intervals; old fused FlashAttention results are historical.
