# EVU checkpoint: 2026-11-18

This source checkpoint follows `b4c28dbe5441789da54a10e02e525928e5cfec94`
(4909 fused cycles). It preserves two ordinary FMA contexts sharing the
existing arithmetic unit, the single-FMA control configuration, and a two-entry
dispatch adapter with `pipe=false, flow=false`. No software arithmetic,
instruction order, output packing or memory traffic changes in this checkpoint.

The adapter sustains one command per cycle with continuous input and a ready
consumer after initial buffering. A full queue cannot refill on its first
dequeue edge with `pipe=false`. Flush behavior and command metadata are retained.

## Server evidence

Input: `rmsnorm_nvfp4_fused_evu_test.log`, an ignored run artifact.
SHA-256: `328d00234c08b979c6b6b028f0e0c02523df551fcbc4e82c273bdd32dbdb2116`.
The emulator reports compilation on November 17, 2026, at 17:00:59.
The server's embedded core SHA is `9f1cd2f942`, dirty=1; it is not a hash of
this local source checkpoint. No EVU-CONFIG or hardware FENCE snapshots are
present to independently identify the server context count or interval deltas.

The guest runs one fused row-major sample, four FP32 rows of 512 elements,
without warmup; lane_sum, quant_rename, quant_banked and quant_direct_first are 1.

| Measurement | Value |
| --- | ---: |
| Timed cycles / CPU instructions | 4183 / 1684 |
| Errors / pending Difftest events | 0 / 0 |
| Checked completions / stores | 1612 / 18 |
| Final status | PASS / GOOD TRAP |
| AMU issue-window blocked / completion blocked | 1602 / 0 |
| Consecutive completions | 1313 |
| Job full / reduction full | 1365 / 154 |
| FMA issue / queue wait / queue idle | 684 / 453 / 45 |
| FMA dependency / operand / feedback wait | 738 / 304 / 54 |
| CMP issue / dependency / queue idle | 272 / 693 / 276 |
| FP4 issue / dependency / queue idle | 128 / 309 / 281 |
| Execute dependency / operand / feedback wait | 1760 / 939 / 132 |
| VRF bank conflict / write blocks read | 240 / 352 |
| VRF grant / response / operand wait | 287 / 1079 / 1191 |
| DIV / FP8 issues | 32 / 16 |
| Memory gets / puts / requests / responses | 171 / 18 / 189 / 189 |
| VMU entry stalls / Ack wait | 21 / 1210 |
| Memory tag occupancy integral / peak | 4968 / 8 |
| Memory tag stall / buffer full / partial writes | 0 / 0 / 0 |

Compared with 4909 cycles, elapsed cycles fall by 726 (14.79%). Both the
dispatch adapter and ordinary FMA context count changed, so this combined
measurement cannot assign the gain to either change separately. Event counts
match the unchanged workload. Wait counters overlap between contexts and
subsystems; they cannot be added into elapsed or recoverable cycles. The
generic ROB EVU latency fields contain invalid/wrapped timestamp differences
and are excluded from performance conclusions.

## Existing local validation

The implementation work passed nine dispatch-adapter Treadle tests, five
FMA-layout/execution-priority tests, one CHIRRTL-only production SRAM/layout
test, and main/test Scala compilation through `evuMmuCheck.test.compile`.
The production-backend arithmetic execution case was cancelled by its
server-only guard. These checks were completed before this checkpoint;
they are not a fresh full regression. No local emu build/run, Verilog
generation or guest binary generation is performed for this checkpoint.
