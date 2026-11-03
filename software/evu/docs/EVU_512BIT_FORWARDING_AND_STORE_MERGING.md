# EVU 512-bit forwarding and partial-store merging

## Scope

This round targets the waits between existing 512-bit arithmetic operations and
the repeated read/modify/write cost of small state stores. The reference log
`evu_softmax_512_before.log` reports 13,960 cycles for first=0 and 12,622 for
first=1. The added compute width has not produced a measured 2x whole-kernel
speedup; the changes below require a new server measurement with the same bin.

The datapath remains 512 bits, with a 16 KiB VRF implemented by 32 physical
64x64-bit, single-port SRAMs. Memory command depth remains four. The instruction
set, NEMU model, software kernel, compute units, execution-context count and
result-buffer depth are unchanged by this round. XSAICache is unchanged.

## Full element-group forwarding

`backend/VectorResultForwarding.scala` selects from the existing FMA, compare and
EXP2 result outputs. A matching complete, unmasked element group can enter an
execution operand collector or the store-data collector in the result-return
cycle. The architectural SRAM write still occurs. A stalled consumer retains
the value in its existing collector; this adds no result cache or result queue.

The intended chains include scale -> subtract -> EXP2 -> sum or store. A bypass
requires the same element-group address, an older producer, and no intervening
writer between that producer and the consumer. Writer checks cover issue
queues, sequencers, arithmetic pipelines, iterative units and staged SRAM
writes. VAT comparisons retain wrapping age semantics. Partial groups, masked
operations, permutation steps and reduction accumulator folds fall back to
the existing paths. Destination WAR/WAW protection remains active. Configurations
without the split FP result sources return no bypass hits.

Store data can also issue directly when its operands return, avoiding a forced
capture-only cycle. If the VMU cannot accept it, the existing collector holds
the operands. Single-element FP32 stores may use the existing E0 mirror after
normal dependency checks; they do not read stale uncommitted values.

## Merge during the existing partial-store Get

`EvuVmuLineCoalescer.scala` starts the Get for a partial aligned-line store
without waiting for a hypothetical successor. While that Get is outstanding,
later disjoint stores to the same line and epoch may join its existing data,
mask and owner entries. A store arriving with the Get response is included.
The preserved line and all collected bytes then form one PutFullData request.

An internal `fullLineData` bit tells the EVU line adapter that preserved bytes
are already present, avoiding a second Get. The logical byte mask is retained
for difftest attribution; the HBL2 transaction remains a full-line write.
Crossing accesses use the existing adapter path. No retained load cache or
CPU/CUTE invalidation mechanism is introduced.

This helps only when stores actually arrive during the available collection
window. It does not eliminate the initial Get for an isolated partial write.
Every merged owner waits for the final physical Put Ack. Overlapping accesses
and different epochs remain ordered. A FENCE drains the transactions. On flush,
unsent work cancels locally, while already-issued bus traffic drains through
the existing adapter and tag-router cancellation path before tags are reused.

The existing software requirement for exclusive CPU/CUTE ownership of a
partial-store destination line remains: external Get/Put read/modify/write is
not atomic against another agent writing that line.

## Formal-interval diagnostics

Optional FENCE snapshots add these fields:

| Record | Field | Meaning |
| --- | --- | --- |
| EXE | `vfw` | Operand handshakes served by full-group forwarding |
| EXE | `vnew` | Cycles a matching result was rejected by an intervening writer |
| FU | `nowork` | Cycles without queued or sequencer work for this issue path |
| FU | `loadwait` | Cycles with active source dependencies on queued/active loads |
| FU | `fwait` | Full-group eligible source RAW waits without a current bypass, excluding known load dependencies |
| VMU | `rmwget` | Partial-store base Gets started by the coalescer |
| VMU | `rmwmerge` | Stores joining during a base Get or before its merged Put issues |

`fwait` indicates a potential forwarding opportunity, not a cache miss or proof
that a future producer will have an eligible full-group result. Existing
`grant`/`resp` counters describe VRF read waits. Different operands and contexts
can wait for different reasons in the same cycle, so these counters overlap
and must not be added as a cycle decomposition. New diagnostic counters are
disabled in the standalone area configuration.

For a later server run, retain the existing command and append
`-- +evu_mem_trace=1`. Extract the formal first=0/1 intervals with:

```sh
/usr/bin/python3 software/evu/scripts/summarize_softmax_fences.py evu_softmax.log
```

## Validation status

Earlier focused runs passed the forwarding selector and three partial-store
Get/Put/error scenarios. A subsequent run passed the EXP2, tagged-memory and
shared-FMA simulation cases, but a separate production-SRAM elaboration case
failed because the same JVM reused a globally cached SRAM Definition. That
elaboration failure is unresolved and is not a passing full-backend regression.

The final source review added zero-source fallback, routed flush coverage,
overlapping-store/load/epoch coverage and diagnostic parsing coverage. These
hardware test additions have not been run. Per the user's instruction, no
further RTL generation, emu execution or software-bin generation is performed.
The lightweight log-parser tests pass all nine cases, and `git diff --check`
reports no whitespace errors.
The final source state has not completed compilation or integration simulation.
No performance gain is claimed until the same-bin, full-completion-boundary
online-softmax and FlashAttention runs complete on the server.
