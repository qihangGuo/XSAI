# Unified Command Window Checkpoint

## Server Evidence

The supplied `rmsnorm_nvfp4_fused_evu_test.log` identifies an emulator built on
November 18, 2026 at 17:05:14. It reports one cold fused row-major sample for
four FP32 rows of 512 elements, with all three direct software options enabled:

| Measurement | Previous checkpoint `51b801f1f` | Unified window |
| --- | ---: | ---: |
| Timed cycles | 4054 | 3747 |
| CPU instructions | 1668 | 1668 |
| EVU completions, whole guest | 1596 | 1596 |
| Checked physical stores | 18 | 18 |
| Pending commands at exit | 0 | 0 |

The new sample reports errors=0, PASS and GOOD TRAP. Cycle reduction is 307,
or 7.57%; throughput speedup is 1.0819x. Software and arithmetic work counts
are unchanged. Window expansion, metadata unification, FIFO removal and
same-cycle row reuse were tested together, not independently. The estimated
214 B reduction in the previously compared main storage arrays is not a
synthesized total-area result; extra selection/control logic also matters.

Raw log SHA-256 (the raw log remains outside Git):

```text
651338433624c456b622de1091a04802e420824838b414b3c902dd65c6d2ea12  rmsnorm_nvfp4_fused_evu_test.log
```

The software prints `fences=0:1`, but this log has no hardware FENCE snapshots
or `[EVU-CONFIG]` record. Wait counters below are end-of-guest totals, not
measured interval deltas. The CPU-only checker adds no EVU arithmetic, making
arithmetic issue counts especially useful; memory and wait totals still need
boundary qualification. Run the same server image with `-- +evu_mem_trace=1`
and use `scripts/summarize_rmsnorm_counters.py` for exact interval evidence.

## Counter Interpretation

These are overlapping conditions, not mutually exclusive cycle categories.
No row below is a guaranteed number of recoverable kernel cycles.

| Counter | Previous | Current | Interpretation |
| --- | ---: | ---: | --- |
| `amu_issue_window_blocked` | 1492 | 778 | Less upstream backpressure; not all remaining stalls are job capacity |
| `job_full_cycles` | 1258 | 260 | Unified 32-row window admits more outstanding commands |
| `job_head_blocked_cycles` | 1265 | 860 | Younger live commands are complete but ordered head still awaits its recorded outcome |
| `job_full_release_cycles` | 150 | 0 | Same-cycle reuse now counts as available; definition excludes successful replacement |
| `dispatch_blocked_cycles` | 343 | 636 | More work reaches public dispatch and exposes downstream bottlenecks |
| `dispatch_fp_fma_queue_blocked` | - | 361 | FMA queue capacity blocks the common dispatch head |
| `reduction_full_cycles` | 141 | 230 | Both reduction owners allocated; a third reduction holds public dispatch |
| `int_queue_idle` | 907 | 993 | Integer queue cannot advance while its shared unit issues nothing |
| `int_dependency_wait` | 622 | 678 | Waiting for older reads/writes, not necessarily arithmetic execution |
| `int_operand_wait` | 335 | 333 | Operands have not arrived in the active sequencer |
| `fp_fma_queue_idle` | 45 | 58 | Much less unused FMA issue opportunity than integer |
| `vrf_bank_conflict_cycles` | 278 | 261 | Same-bank different-row competition, not the sole VRF wait source |
| `vrf_write_blocks_read_cycles` | 356 | 402 | 1RW writes block some reads despite fewer read/read conflicts |
| `vrf_read_response_wait_cycles` | 898 | 890 | Collector awaits data after request acceptance |
| `vrf_read_grant_wait_cycles` | 267 | 243 | Collector request has not yet won a read grant |

The arithmetic work counts remain integer=886, FMA=684, CMP=272,
FP8=16, FP4=128, DIV/SQRT=32. Integer rearrangements account for 734/886
issues: 224 extracts, 256 inserts, 248 gathers and six broadcasts. The other
152 are integer mask/scale bookkeeping. Integer issued in 886 cycles and
had pending work without issue in 1057 cycles: only 45.6% of these 1943
pending-work cycles issued. Busy time happens to equal 1943 here; the RTL's
busy predicate is not generally identical to pending-work time.

Head-blocked specifically requires at least one younger completed live command;
it does not distinguish a head still computing from a result awaiting transport.
The integer 993 queue-idle cycles overlap heavily with dependency=678 and
operand=333. These cannot be added. FMA issue=684 and queue-idle=58 do not
suggest that adding FMA multipliers is the first intervention. Public-dispatch
636 is accounted for in this run by FMA queue=361, reduction owners=230 and
integer queue=45; this numeric equality does not make all diagnostic conditions
disjoint in other workloads. A blocked public head can delay independent work
for another unit. VRF response wait also includes inherent synchronous-read
latency; it is not evidence of 890 removable cycles or insufficient bank count.

## Next Experiments

Hardware: first diagnose integer queue successors and the public dispatch head.
A bounded second ordinary integer context can prepare an independent aligned
slide/gather while the first waits, sharing the current unit. Keep generic
multi-EG permutation ownership on the original context. Record whether the
successor has ready sources before adding it; retain VAT age checks, writeback
reservations and collector invalidation. Separately measure reduction-owner
release coinciding with blocked dispatch, and pending completions belonging to
the ordered head. Same-cycle owner credit or circular oldest-first completion
selection are smaller candidates if those coincidences are frequent. Do not
increase all queue depths or bypass the public head without tracking hazards
of skipped instructions.

Software: test eliminating the 16 zero-offset FP4 output inserts. Each 64-byte
output group begins with an 8-byte conversion into v18 and a slide-up by zero
into v30. Converting that first block directly into v30 should remove the copy;
later blocks retain their exact order, one output buffer and immediate full
stores. Target timed commands 1594 to 1578 and integer issues 886 to 870,
with identical arithmetic and 18 stores. Verify all destination masks and
poisoned tails, including steady-state overlap and the final batch, before
server timing. This is a candidate, not an implemented or measured speedup.

## Cleanup And Validation

- Removed the retired FlashAttention chunk-max script; kept all 11 current C
  guests and the independent MMU reference checks. The stage RMSNorm source
  is also included by the fused test and must not be removed.
- Removed matrix-coupled archived documents/PDF, two retired Flash checkpoints,
  superseded ABI plans/progress and stale handoff/softmax plans. Historical
  contents remain in Git. Replaced the old softmax performance report with
  [ONLINE_SOFTMAX_CURRENT.md](ONLINE_SOFTMAX_CURRENT.md).
- Removed `EvuCompletionLoopSpec`: production HW-dialect cycle checking now
  lives in `EvuAreaTopSpec` without emitting Verilog.
- Removed `EvuRegisterFileSpec`: current bank-mapping, synchronous-VRF and
  byte-mask tests cover its small debug-snapshot instance and additionally
  exercise production 512-bit geometry. Kept unique functionality regressions.
- Python parser/source unit tests: 79 PASS. Softmax source interpreter and
  RISC-V syntax checks: 182 schedules PASS, including exact default work counts.
- After cleanup, reran all 15 unified-command-table, scoreboard, job-counter and
  external-dispatch Treadle cases: PASS. Local Markdown targets and whitespace
  checks pass.
- Unified-window Scala/Treadle and production structure/cycle checks are
  documented in [EVU_UNIFIED_COMMAND_WINDOW.md](EVU_UNIFIED_COMMAND_WINDOW.md).
  No local emu build/run, Verilog generation or guest/native bin generation.
