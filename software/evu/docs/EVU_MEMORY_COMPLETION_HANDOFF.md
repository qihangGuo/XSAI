# EVU memory completion handoff

This document records the pre-migration implementation and validation plan.
Its matrix-specific sections and old binary-compatibility instructions no
longer apply after removal of EVU/CUTE coupling. Use
[`EVU_PURE_VECTOR_PLAN.md`](EVU_PURE_VECTOR_PLAN.md) for current build and
regression commands; the ordinary VMU handoff optimizations remain enabled.

## Baseline and scope

The November 5, 2026 13:42:40 emulator in `evu_softmax_512_before.log`
reports 6,600 cycles for first=0 and 5,536 for first=1. The formal FENCE
intervals are 2:3 and 9:10. Entry stalls are 1,235/2,636 cycles, with average
live VMU command counts of 2.88/3.30 at depth four. These are the baseline
before this change, not results measured from the new implementation.

This change shortens completion handoffs and bounds write arbitration delay.
It keeps DLEN/MLEN=512, memoryCommandDepth=4, eight physical memory tags, the
existing 16 KiB VRF with 1RW banks, the ISA, and the guest software unchanged.
No XSAICache, HBL2 protocol, CUTE or NEMU changes are required.

## Returning command credit

`EvuCommandScoreboard` considers the current accepted completion when testing
the memory window limit. A completion returns credit only if its job is live
and owns a memory reservation. A full window can admit an independent command
on that cycle. Compute completions and non-owners do not return memory credit.

The release input remains driven by `io.completion.fire` in `EvuVectorBackend`.
An offered but backpressured completion cannot release a slot. The old job's
register reservations are not removed from the current-cycle hazard masks.
Ordinary pairs retain Saturn EG ordering; special/matrix paths retain their
whole-register guards. This does not enable same-cycle reuse of a live job ID,
VAT, local tag, or physical bus source. Those ownership rules are unchanged.

## Final-response forwarding

`EvuHbl2LineAdapter` forwards a noncrossing final response when no buffered
response is waiting. A load response, final Put Ack, or terminal single-line
error can bypass the response register. A successful partial-store Get must
still perform its Put; it is never treated as a successful store completion.
The buffered and bypass paths share the returned-load alignment logic.

`EvuVmuLineCoalescer` forwards a response only from its final `waiting` state,
for a noncrossing line with exactly one logical owner, no owner joining on
that cycle, and no older buffered response. Its `reading` state is excluded:
an RMW base Get must transition to a prepared Put first. Multi-owner replies,
crossing requests and simultaneous joins use the existing buffered path.

Both modules accept a valid physical response into existing storage even
when the logical consumer is stalled or a buffered reply wins arbitration.
A directly consumed response clears its ownership exactly once. Flush/drop
suppresses forwarding; already-issued bus transactions still drain and the
existing cancellation route reclaims their tags. Errors retain their fault
and owner information. FENCE completion continues to depend on physical Acks
and all outstanding work draining.

For an uncontended noncrossing single-owner request, the physical response
can now reach the logical consumer without the two former response-register
handoffs. This does not eliminate the downstream load/VRF/store completion
tracking, nor imply that every request saves two kernel cycles.

## Write arbitration

`EvuVmuRequestArbiter` selects between already-ready Saturn load/store requests.
The normal bound stays at eight successful load grants while a store waits.
When registered memory occupancy reaches depth minus one (three for depth
four), the bound becomes two load grants. HBL2 backpressure does not consume
the grant budget. A store grant, disappearance of the store, or flush resets
it. This uses registered occupancy rather than the completion-credit bypass
to avoid feeding the response path back into arbitration.

`EvuVmuLineArbiter` prioritizes prepared RMW Puts over ordinary line requests.
With both classes ready, at most two prepared Puts are granted before an
ordinary request. Separate narrow round-robin index arbiters keep fairness
within each class; the payload multiplexer and line storage remain shared.
The coalescer's existing overlap/epoch/data guards determine eligibility
before arbitration. Priority does not bypass those guards or acknowledge a
store early. A stalled downstream does not consume priority grants.

## Validation status and server checks

The first server build after this change failed in firtool, before Verilator
compilation. `xsai_build.log` reports a combinational cycle at `EvuTop`:
backend busy -> FENCE completion valid -> completion-arbiter ready -> accepted
backend completion -> same-cycle command credit -> special-command admission
-> matrix startAllowed -> matrix busy -> backend busy.

The redundant dependency was in `EvuMatrixDequant`: busy used active, and
active included `ownsHead = queue.deq.valid && (reading || startAllowed) &&
!flush`. Since busy already included queue.deq.valid, ownsHead could not change
its value. Busy now explicitly ORs reading, queue occupancy and occupied
arithmetic/output stages. The active signal still retains ownsHead for shared
VRF ownership. This is a Boolean-equivalent simplification, not an extra
register or an early completion; same-cycle credit and final-Ack forwarding
remain enabled. Matrix data waiting for ownership still prevents FENCE from
completing.

`EvuCompletionLoopSpec` adds a full EVU wrapper check through firtool, with
production widths and the real control/backend/memory connections. Existing
CHIRRTL-only elaboration checks do not exercise firtool's cycle detection.
Run this check in its own Mill invocation because SRAM definitions are cached
across elaborations in the same JVM. The matrix ownership test also checks
that toggling startAllowed cannot clear busy for queued work.

Added test sources cover full-window release/accept at depths 1/2/4, nonmemory
completion credit rejection, register/matrix guards, same-cycle end-to-end
responses, retained responses under backpressure, errors, reordered tags,
simultaneous load joins, RMW Get versus final Ack, and bounded arbitration.
Existing adapter/coalescer/RMW tests were adjusted where they assumed a
mandatory response-register cycle. They retain crossing, multi-owner, error,
overlap and flush coverage; adapter tests also cover flush on the response
cycle and buffered-response priority.

Only source review and whitespace checks were performed locally. Per the
user's restriction, no Scala/Mill compilation, hardware simulation, RTL
generation, emu run, or guest-bin generation was performed. Added tests have
not been executed. Server regressions are required before accepting the
change, including combinational-loop checks and the complete memory path.
The response bypass extends combinational paths; physical timing has not
been verified.

From the server repository root:

```sh
mill -i xiangshan.test.testOnly evu.EvuCompletionLoopSpec

mill -i xiangshan.test.testOnly evu.EvuMatrixDequantSpec \
  evu.EvuControlBarrierSpec

mill -i xiangshan.test.testOnly evu.EvuCommandScoreboardSpec \
  evu.EvuVmuRequestArbiterSpec evu.EvuMemoryResponseBypassSpec \
  evu.EvuHbl2LineAdapterSpec evu.EvuVmuLineCoalescerSpec \
  evu.EvuRmwStoreCoalescingSpec evu.EvuMemoryTagRouterSpec \
  evu.EvuMatrixWritebackSpec

EVU_LONG_TREADLE=1 mill -i xiangshan.test.testOnly evu.EvuMemoryWindowSpec
```

Rebuild emu with the existing configuration/build command. The existing
512-bit NEMU library and guest bin are compatible. Run the same online softmax
bin with `-- +evu_mem_trace=1` and compare formal first=0/1 intervals against
6,600/5,536 cycles, entry stalls, Ack wait, live commands/tags, Get/Put counts
and diagnostic drain. Counters overlap and must not be summed as saved cycles.
Also run the memory-path diagnostic and FlashAttention with difftest and
independent correctness checks. Arbitration can change merging opportunities
and traffic ordering, so kernel cycles and correctness remain the acceptance
criteria; no speedup is claimed before those server runs.
