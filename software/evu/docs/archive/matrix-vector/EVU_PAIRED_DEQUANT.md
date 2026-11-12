# Paired-row matrix output and local read scheduling

Date: 2026-11-09. Source implementation complete; hardware build, numerical
regressions, performance and physical timing are pending on the server.

## Functional changes

- Default FMA allocation returns to one ordinary and two reduction contexts.
  The optional two-ordinary-context regression still tests that supported
  configuration; no top-level CONFIG or build command is added.
- MatrixVectorBridge stores exactly two complete tiles, including the active
  head. The old third active-tile copy is removed. For 8x8 INT32 tiles this is
  512 bytes of payload, plus metadata and control. Flush empties both slots.
- At DLEN=512, each beat holds two rows of the same 8x8 microtile. Four
  successful beat handshakes drain a microtile. Backpressure pauses progress;
  this is not a four-cycle promise under arbitrary scale or memory stalls.
- Dequantization repeats the same eight column scales in both halves and uses
  two distinct row scales. Aligned row pairs use the existing two 512-bit scale
  chunks. INT32 conversion and both FP32 rounded multiplies retain their order.
  Each row retains its own flags; inactive lanes contribute no flags.
- Matrix stores carry two addresses. Aligned rows in different lines update
  two available/collecting entries per cycle. Same-line, unaligned or capacity
  conflicts fall back to the original ordered fragment machinery. No half is
  discarded, existing line locks remain until Ack, and last is consumed only
  after both rows. Memory tags, HBL2 protocol and final-Ack completion remain.
- VRF read arbitration has three-bit saturating demand ages, oldest/last-source
  hints and round-robin selection within a priority class. Age advances on
  successful read arbitration opportunities, not blocked SRAM write cycles.
  Request identity changes clear age. Demand still precedes speculative hints,
  equal-row sharing remains, and already reserved writes keep their guarantees.
- EXP2 can hint a safe full FP32 first EG of the FIFO head while its current
  command is at the tail. It reuses the existing rs2 collector; masked/E0,
  vstart and older-writer cases stay conservative. There is no additional
  arithmetic unit, ordinary context or operand data buffer.
- Reduction arithmetic is unchanged. New counters distinguish accumulator
  data ready but not issued from issue using a same-cycle matching slot return.

The only CUTE source change is an optional two-bit probe of raw MatrixD valid
and ready when difftest is enabled. Functional CUTE and ACC routing is unchanged.
XSAICache, utility, CPU functionality, NEMU and guest binaries are unchanged by
this round. Existing earlier worktree changes in those files are preserved.

## Diagnostics and interpretation

Use `-- +evu_mem_trace=1`. New cumulative FENCE families:

| Record | Interpretation |
| --- | --- |
| EVU-BRIDGE | Raw MTE output blocking, EVU-side backpressure, bridge output blocking, 0/1/2-slot occupancy cycles, accepted final tiles, accepted interarrival gaps |
| EVU-DEQUANT | Task-active input-empty cycles, scale/ownership/output wait, issued groups and active elements |
| EVU-MATRIXSTORE | Accepted parallel row pairs, serial fallback pairs, output blocking and line merges |
| EVU-READHINT | Per-reader-class last-operand grants and blocked requester-cycles |
| EVU-HANDOFF | EXP2 safe lookahead eligibility and eligibility while its FU is idle |
| EVU-TAIL | Per-FU/stage ready-but-not-issued context-cycles and matching-return issues |

`EVU-MATRIX-TASK` is a per-Launch-to-final-Ack summary, identified by task number
and launch PC. `peak` is the task-local occupancy maximum, `full` counts full
occupancy, and `burst` is the maximum consecutive accepted-final-tile run.
Compare each task with the existing QK/PV phase sequence. Cumulative FENCE
counters can be subtracted; task peaks/burst maxima must not be subtracted.
Accepted gaps/bursts describe the already backpressured interface, not an
unthrottled MTE production trace. A full queue cycle need not be an MTE stall.

Observe MatrixD before ready gating: the current CUTE vectorResult.valid depends
on consumer readiness, so vectorResult.valid && !ready alone would miss stalls.
MTE total block and EVU-side block may overlap and are not additive lost time.
All newly added diagnostic state is conditional on enableDifftest or
diagnosticCounters and excluded from the standalone area configuration.

## Server validation

Sync XSAI_external_vector including modified CUTE and difftest source files.
Rebuild emu with the existing command. This round preserves the ABI2 guest ISA
and the NEMU row callback: existing ABI2 bins and NEMU can be reused. The emu's
generated difftest event layout must be regenerated: a single paired event is
checked with two ordered calls to the existing row reference function.

```sh
mill -i evuCheck.test.testOnly evu.MatrixVectorBridgeSpec
mill -i evuCheck.test.testOnly evu.EvuMatrixDequantSpec
mill -i evuCheck.test.testOnly evu.EvuMatrixStoreBufferSpec
mill -i evuCheck.test.testOnly evu.EvuPairedMatrixPipelineSpec
mill -i evuCheck.test.testOnly evu.EvuMatrixWritebackSpec
mill -i evuCheck.test.testOnly evu.EvuReadPrioritySpec
mill -i evuCheck.test.testOnly evu.EvuIndependentOperandSpec
mill -i evuCheck.test.testOnly evu.EvuSaturnExp2Spec
mill -i evuCheck.test.testOnly evu.EvuInterleavedReductionSpec
mill -i evuCheck.test.testOnly evu.EvuAreaTopSpec
```

Run the same softmax bin (baseline 6624/5437 cycles) and FlashAttention cold/warm
variants with numerical checks and difftest. Check matrix byte count rather
than expecting beat count to stay constant; a full 512-bit pair carries 16
active elements. The difftest checked_matrix_beats statistic remains row-based.
Compare total cycles, bank conflicts, last-operand grants, memory requests,
parallel/fallback pairs and task backpressure. Do not infer a speedup or PPA
benefit before these tests and synthesis/timing checks.

Local checks: Python log-summary tests and git diff --check. No Scala compile,
RTL elaboration/generation, emulator run, bin build or synthesis was performed.
