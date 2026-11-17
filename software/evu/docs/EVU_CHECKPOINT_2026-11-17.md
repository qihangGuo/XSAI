# EVU checkpoint: 2026-11-17

This checkpoint preserves the current RTL, software, tests and area/timing
experiments together. It is a source snapshot, not a claim that every optional
configuration has been validated by the server run below.

## Preserved changes

- Partial-byte result forwarding with request coverage and recent-result
  retention, keeping VAT age checks and collector invalidation.
- FP32 single-element-group min/max reduction through the comparison tree and
  seed comparison, with the existing path retained for other cases.
- AMU completion lookahead across the completed prefix being cleared, including
  completion reception and consecutive-reception counters and an A/B config.
- A fused-only row-major profiling entry with a scalar reference checker and
  direct first-block input consumption, removing 32 zero-offset slides.
- Counter parsers, profiling instructions, directed tests, execution-control
  predecode, integer-width predecode and area/timing experiment entry points.

## Server measurement

Input log: `rmsnorm_nvfp4_fused_evu_test.log` (ignored build/run artifact).

SHA-256: `046fb921956b5d534691334a016e5750f27104aef479f5e2e83470567a250cd9`

The emulator reports compilation at November 17, 2026, 13:41:37. The guest
reports four rows of 512 FP32 elements, one sample, no warmup, format 11,
`profile=fused_row_major`, `oracle_dlen=512`, and
`lane_sum=1 quant_rename=1 quant_banked=1 quant_direct_first=1`.

| Measurement | Value |
| --- | ---: |
| Timed fused cycles | 4909 |
| Timed retired CPU instructions | 1684 |
| Errors | 0 |
| Guest measurement FENCE indices | 0:1 |
| Difftest checked EVU completions | 1612 |
| Difftest checked stores | 18 |
| Difftest pending events | 0 |
| Final status | PASS / GOOD TRAP |

Previous measurements of this fused-only entry were 4979 cycles before the
direct-first software change and 4911 cycles with that software change on the
older RTL. The completion update therefore changes the observed time by only
two cycles, despite enabling consecutive completion reception. These are single
samples; they do not establish a robust two-cycle speedup. The historical
multi-case 5009-cycle result has a different execution history.

## Selected end-of-guest counters

| Counter or group | Value |
| --- | ---: |
| AMU completion fire / blocked | 1612 / 0 |
| AMU completion lookahead / consecutive | 1059 / 1059 |
| AMU issue-window blocked / barrier | 2325 / 49 |
| Preflight serial stall | 422 |
| Job full | 450 |
| Execute dependency / operand / feedback wait | 586 / 1202 / 288 |
| Execute writeback / FU wait | 10 / 126 |
| VRF operand / grant / response wait | 1456 / 166 / 1352 |
| VRF bank conflict / write blocks read | 60 / 314 |
| VRF prefetches / used / discarded | 1082 / 935 / 147 |
| FMA issue / queue wait / queue idle | 684 / 651 / 222 |
| FMA idle with work / operand wait / dependency wait | 480 / 293 / 104 |
| FMA feedback / result-slot wait | 54 / 0 |
| CMP issue / priority issues | 272 / 128 |
| CMP dependency / operand / feedback wait | 101 / 122 / 234 |
| CMP queue idle / idle with work / result-slot wait | 24 / 604 / 0 |
| DIV / FP8 / FP4 issues | 32 / 16 / 128 |
| Memory gets / puts / requests / responses | 171 / 18 / 189 / 189 |
| VMU entry stalls / Ack wait | 21 / 1210 |
| VMU command occupancy integral / tag occupancy integral | 2351 / 4968 |
| Memory/compute overlap | 654 |
| Tag stalls / VMU buffer full / partial writes | 0 / 0 / 0 |

Only the selected fused call performs EVU arithmetic; its checker uses CPU
arithmetic. Arithmetic event counts can therefore describe one fused execution.
The 1612 completions also include the initial SETVLI and preparation FENCE,
outside the timed interval. No hardware FENCE snapshots are present, so elapsed,
idle and memory-related totals are not exact deltas of the 4909-cycle interval.
For example, `evu_fma_no_work_cycles=193687` includes CPU initialization and
reference checking. Wait counters may overlap across contexts and subsystems;
occupancy integrals count occupied entries per cycle rather than elapsed cycles.

Completion reception is no longer backpressured in this run, while job-full,
issue-window, VRF and execution wait totals are unchanged from the prior
4911-cycle run. Preflight serial stalls fall from 1085 to 422 without an
equivalent change in memory execution or elapsed time. FMA instruction handoff
and operand collection remain useful next A/B targets; these counters do not
prove the benefit of another context or a particular forwarding change.

## Validation

At checkpoint creation, all 52 RMSNorm Python/source/preprocessor/syntax tests
passed, and `git diff --check` passed. Earlier work compiled the main/test Scala
sources and passed targeted Treadle AMU completion cases. The final additional
consecutive-completion/pointer-wrap case was interrupted, not reported passed.
No local emulator build/run, Verilog generation or guest binary generation was
performed for this checkpoint. Server timing and Difftest evidence refer to the
specific default-path run described above, not every preserved experiment.
