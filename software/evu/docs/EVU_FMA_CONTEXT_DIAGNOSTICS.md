# FMA contexts and bottleneck diagnostics

Diagnostics introduced on 2026-11-09. Counter definitions below remain current;
the rollout chronology and old baselines are historical. Current architecture,
server acceptance and local checks are recorded in
[EVU_CHECKPOINT_2026-11-19.md](EVU_CHECKPOINT_2026-11-19.md).

Paired-row work temporarily restored the default ordinary FMA count to one after
the earlier two-context measurement did not show an aggregate speedup. On
2026-11-17 the fused RMSNorm experiment enables two again, with a separate
single-context A/B configuration. Its baseline is 4909 cycles with one ordinary
context. A subsequent 4183-cycle run includes both two contexts and the
two-entry dispatch adapter; an isolated context A/B remains required. See
[RMSNORM_FMA_TWO_CONTEXT_AB.md](RMSNORM_FMA_TWO_CONTEXT_AB.md) for this experiment.
The retired paired-row/matrix-coupled history remains available in Git only.

Subsequent ABI 2 work changes configuration instructions and the CSR snapshot.
For the combined checkout, rebuild emu, NEMU and guest binaries together as
documented in [EVU_CONFIG_ABI_V2.md](EVU_CONFIG_ABI_V2.md). The old-bin reuse
instructions below apply only to this scheduling change in isolation.

## Functional change

The default EVU has two ordinary FMA sequencers and two reduction sequencers
sharing the existing single 512-bit arithmetic path. CMP retains one ordinary
and two reduction sequencers; EXP2 retains one sequencer. There are still only
two globally allocated reduction owners, shared by sum/max work.

`ExecuteContextLayout` assigns reduction owners independently of the number of
ordinary sequencers. FMA owners now occupy context indices 2/3; CMP owners
remain at 1/2. Do not use a fixed `context - 1` mapping. Queue dequeue remains
in order, and the first compatible available context accepts the head command.
Once accepted, independent contexts may issue element groups out of order under
the existing VAT/EG RAW, WAR, WAW and writeback-reservation checks. Arithmetic
pipelines, result routing and the bounded E0/reduction-tail priority arbiter are
shared, with no increase in arithmetic issue width.

This can let an independent ordinary operation run while another ordinary
operation waits for data. It cannot bypass a blocked queue head, create missing
CPU commands, eliminate true dependencies or increase SRAM ports. The additional
context adds real operand buffers, collectors, control state and arbitration;
its area and timing cost must be measured, not assumed negligible.

`EvuParams.fmaOrdinaryContexts` defaults to 2. Use
`DefaultExternalVectorSingleFmaContextMatrixConfig` for a focused server-side
comparison without changing ISA or software. Legacy non-interleaved issue
structures keep their single general sequencer. The default 2-context behavior
applies to the split FP, interleaved configuration used by the current emulator.

VLEN=4096, DLEN/MLEN=512, VRF=16 KiB, 32 physical 64x64-bit SRAM banks, 1RW per
bank, memory command depth=4 and 8 physical tags are unchanged. No CPU, HBL2,
CUTE or NEMU source and no guest kernel are changed by this round. Existing
dirty modifications from prior rounds are not reverted or attributed to it.

## FENCE snapshots

Rebuild the emulator on the server with the usual command and reuse the existing
512-bit NEMU library and guest bin. Add these simulator arguments:

```sh
./build/emu \
  --diff /nfs/home/guoqihang/workspace/NEMU_evu/build/riscv64-nemu-interpreter-so \
  -C 100000000 \
  -i software/evu/build/online_softmax_evu_test-riscv64-xs.bin \
  -- +evu_mem_trace=1 2>&1 | tee evu_softmax_contexts.log

/usr/bin/python3 software/evu/scripts/summarize_softmax_fences.py \
  evu_softmax_contexts.log --format table
```

The existing record format also exposes every new counter:

```sh
/usr/bin/python3 software/evu/scripts/summarize_softmax_fences.py evu_softmax_contexts.log
```

Snapshots still occur at completed FENCEs. For the unchanged w=1, diag=1 bin,
formal first=0/1 intervals are 2:3 and 9:10. Keep the full raw log. New record
families are optional for old logs, but a family present at only one endpoint
is an error rather than an invented zero. CONFIG describes geometry and must
match across the interval; it is not a counter to subtract.

| Record | Fields and units |
| --- | --- |
| CONFIG | DLEN, MLEN, VLEN and configured context counts |
| OP | Per operation class: first-issued instructions, micro-op issues, active FP16/FP32 lanes, waiting context-cycles, completed instructions, summed issue-to-write-completion latency |
| SCHED | Per FU: ordinary/reduction queue-head blocking, ready-but-idle cycles, arbitration losers, bank reservation waits and occupied context-cycles |
| RED | Per FU and stream/merge/fold stage: issues, active lanes and waiting context-cycles |
| READ | Per FMA/CMP/EXP2/store/other reader: request-cycles, granted demand reads, arbitration/writing blocks, return latency, held data and prefetch consumption |
| MEM | Logical bytes, requested beat bytes, enabled store bytes, request grants, local-tag and downstream stalls, arbitration losses and response backpressure |
| LAT | Load/store accepted-response counts in <16, 16-63, 64-255 and >=256-cycle latency bins |

### Computation and scheduling definitions

OP classes cover mul, add/sub, max, EXP2, FMA and other operations on the three
observed FP paths. Single-element operations have separate E0 classes; sum/max
reductions have separate classes. `other` is not a promise of complete opcode
coverage for integer, conversion, division or quantization paths. No per-PC
associative counter table is instantiated.

`inst` counts the first issued micro-op, not queue admission. `e16/e32` count
active lanes using the final FU byte mask and destination element width. Masked
and tail lanes do not count. A reduction stream counts participating lanes;
merge counts the partial accumulators' lanes; fold counts the shrinking active
half. These are executed lane operations, not algorithmic FLOPs, and do not
measure physical toggle activity. FMA has two arithmetic operations per useful
lane; EXP2 and max must not be assigned the same FLOP convention blindly.
The table's lane-use percentage is emitted only for an unmixed FP32 sample with
known DLEN; it is active FP32 lanes / (issues * DLEN/32), not MFU.

OP `latency/completed` measures first issue to the backend's architectural write
completion. It includes waiting between micro-ops and the final write path.
It excludes CPU preflight and time before first issue. Latencies of overlapping
commands are not additive kernel time.

`ordinary`/`reduction` count queue-head commands unable to enter a compatible
context; `ordinary_idle` additionally requires no FU issue in that cycle. They
do not prove the waiting command is independent or has ready source data.
`ready_idle` means at least one context has its inputs/dependencies ready but
the FU issues nothing; write reservations can still prevent issue. `eligible_idle`
means the shared arbiter has a valid output but the FU does not accept it.
`losers` counts ready, result-slot-eligible contexts losing on a cycle when
another context successfully issues. It is a context-cycle count, not lost
FU cycles. `bank_reserve` and result-slot waits must remain separate.
`dispatch_red` records a matching command blocked by global reduction-owner
allocation. The old FU `dispatch` field is now retained by the parser too.

Existing `issue/idle/nowork` remains the mutually exclusive FU time partition.
Other reasons can overlap across contexts and resources. Existing `arb` still
means successful issue with competing candidates; its historical meaning is
not silently changed. Existing `loadwait` is a register/EG overlap diagnostic,
not a precise critical-path or nearest-producer attribution. It is insufficient
by itself to justify dependency-priority memory arbitration.

### VRF and memory definitions

READ counters count requester-cycles or accepted reads, not a union over the
whole VRF. Several readers may wait together. `read_block` covers arbitration
when the target bank is not writing; `write_block` covers demand grant waiting
while it is writing. `response` includes the normal synchronous SRAM read
latency. `held` includes prefetched results awaiting consumption. Scalar and
full-EG bypass hits do not enter these SRAM collectors. `other` includes mask,
initial reduction, special and other-FU reads.

MEM `logical_load/logical_store` sums accepted ordinary memory commands' VL
times element bytes. `load_bytes` counts complete requested VMU beats, including
overfetch for E0/tails; `store_bytes` counts enabled bytes of accepted store
requests. They are before line coalescing and are not physical HBL2 traffic.
Continue using VMU Get/Put/RMW counters for that boundary. `no_tag` measures
the local remap pool; shared-router exhaustion appears as downstream blocking.
`load_lost/store_lost` count a ready class losing on a successful grant to the
other class, not all pending cycles. LAT includes response acceptance backpressure
and measures the VMU boundary, not pure cache latency. Flushed requests drain
normally but are excluded from normal latency histograms and counted separately
when their cancelled responses return.

## Optional PC attribution

Use `-- +evu_mem_trace=1 +evu_cmd_trace=1` only for a diagnostic run. The default
omits command prints. Accepted normal vector commands emit a ticket plus VAT,
job, epoch and PC. The ticket survives VAT reuse and flush. FP contexts emit
their primary wait totals at the last issue; the backend emits execution timing
at write completion. The wrapper emits final completion, including memory Ack.
Special/zero-length wrapper completions are outside this command trace.

```sh
/usr/bin/python3 software/evu/scripts/summarize_evu_commands.py \
  evu_softmax_contexts.log --fences 2:3
/usr/bin/python3 software/evu/scripts/summarize_evu_commands.py \
  evu_softmax_contexts.log --fences 9:10
```

The host joins summaries within each VAT lifetime, checks ticket/job/epoch/PC
ownership, handles completion print ordering, and discards unfinished flushed
commands. Only commands accepted and completed inside the selected interval
are included. Without `--fences`, warmup and diagnostics are included explicitly.
`mean_queue` is backend admission to context allocation; `first_wait` is context
allocation to first issue; `mean_execute` is first issue to write completion.
`mean_total` is wrapper acceptance to completion. These boundaries differ;
none includes CPU preflight. Per-context dependency/operand/feedback totals
are not summed into an estimate of recoverable kernel cycles.

## Diagnostic cost and area boundary

All new counters, timestamps and command-summary state are generated only under
`diagnosticCounters` or `enableDifftest`. With diagnostics disabled, new perf
fields are constant zero. Plusargs control printing, not synthesis removal.
The standalone area configuration disables difftest; its regression rejects
new `diag_*` registers and retains the 32 1RW SRAM macros. Optional existing
`--keep-perf` does not re-enable these detailed diagnostics. Actual synthesis
still needs to confirm pruning and quantify the functional extra context.

Diagnostics contain per-class accumulators, small per-VAT timestamp arrays and
eight tag timestamps. They have real compile/simulation cost, even though they
are absent from the area configuration. No all-pairs speculative queue scheduler,
large trace RAM, arbitrary-PC table or per-cycle default printing was added.

## Validation and acceptance

Baseline: formal cycles 6652/5353 from the November 5 16:25:41 emulator. Do not
claim speedup until the new emulator finishes the same bin with the same FENCE
boundaries and checks. Expect identical instruction/FU work for pure scheduling;
different memory interleaving can change line merging and physical request counts.

Lightweight checks run locally: Python summary regressions, old-log parsing and
`git diff --check`. Scala tests are added but NOT compiled or run locally.
No Java/Mill process, RTL generation, emulator or bin build was started.

Server checks (run SRAM-backed suites separately):

```sh
mill -i evuCheck.test.compile
mill -i evuCheck.test.testOnly evu.EvuDiagnosticSpec
EVU_FULL_BACKEND_TREADLE=1 mill -i evuCheck.test.testOnly evu.EvuFmaContextSpec
mill -i evuCheck.test.testOnly evu.EvuInterleavedReductionSpec
mill -i evuCheck.test.testOnly evu.EvuAreaTopSpec
```

The FMA regression holds back a load, verifies a younger independent add can
finish, then checks RAW/WAW/WAR chains, two sums and their scalar consumer.
The diagnostic regression checks single-element classification, masks, FP16/32
lane counts and reduction folding. Retain the existing softmax RTL/NEMU, memory
reordering/error/flush/FENCE and EXP2 regressions, including
independent numerical accuracy checks in addition to CHECK=0 performance runs.

Accept the context only if both softmax cases and broader EVU tests show an
acceptable tradeoff in total cycles, correctness and PPA. If ordinary-blocked
idle cycles fall but VRF contention offsets them, do not infer success from
the counter alone. No further arbitration, reduction arithmetic or width change
is bundled into this round.
