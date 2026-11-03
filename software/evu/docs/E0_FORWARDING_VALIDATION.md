# E0 result forwarding and execution wait counters

## RTL behavior

The ordinary VMU window remains four commands. The existing software and ISA
are unchanged. Rebuild the emulator with the usual
`CONFIG=DefaultExternalVectorMatrixConfig` command.

The VRF keeps a small mirror of the committed low FP32 word of each vector
register: 32 x 32 bits of data plus four byte-valid bits per register.
Only physical SRAM commits update it, including pipeline, staged load and
iterative writes. A masked write updates only enabled bytes; writing another
element group or a zero mask cannot replace E0. Reset/flush invalidates the
mirror. RegisterAccess's existing reset transactions subsequently repopulate it.

ExecuteSequencer can read the mirror for unmasked 32-bit single-element
operations and the FP32 scalar-broadcast source. Full vector operands, masks,
gathers, and reduction input streams continue to read SRAM. Each operand read
waits for its own RAW and buffered-write hazards; final issue still checks
RAW, WAR and WAW. Captured scalar
operands remain stable under execution backpressure and across vector chunks.

This first version forwards the committed result on the following cycle. It
does not bypass a producer that has not committed, change reduction/addition
order, suppress the architectural VRF write, or add a physical SRAM port.

## Measurements

Default final PERF output adds these counters:

| Counter | Meaning |
| --- | --- |
| `evu_execute_dependency_wait_cycles` | An active execute sequencer is blocked by RAW/WAR/WAW. |
| `evu_execute_feedback_wait_cycles` | It waits for a reduction accumulator. |
| `evu_execute_operand_wait_cycles` | At least one required operand has not arrived. |
| `evu_execute_permutation_wait_cycles` | It waits for gather/slide data. |
| `evu_execute_writeback_wait_cycles` | A local, VRF-bank or shared-result reservation is unavailable. |
| `evu_execute_fu_wait_cycles` | Operands/reservations are ready but FU arbitration or execution backpressures. |
| `evu_vrf_read_grant_wait_cycles` | A current operand's SRAM request is denied. |
| `evu_vrf_read_response_wait_cycles` | A current operand has issued a read and awaits its response, including the launch cycle. |
| `evu_scalar_result_forwards` | Number of operand handshakes served by the E0 mirror. |

Each sequencer selects one primary wait reason in the table's first six rows,
in priority order dependency, feedback, operands, permutation, writeback, FU.
The aggregated counters count cycles with any matching sequencer. Different
sequencers and collectors can wait for different reasons simultaneously; do
not add these counters or treat them as exclusive operator cycle components.
They do not cover work still queued before an execute sequencer.

For interval measurements, append the existing optional simulation plusarg
after all emulator options:

```sh
./build/emu --diff /path/to/riscv64-nemu-interpreter-so -C 100000000 \
  -i software/evu/build/online_softmax_evu_test-riscv64-xs.bin \
  -- +evu_mem_trace=1 2>&1 | tee evu_softmax.log
```

Each completed XV_FENCE emits the existing `[EVU-VMU]` snapshot and a compact
`[EVU-EXE]` snapshot. `dep/operand/feedback/perm/wb/fu` are execution waits;
`grant/resp` are SRAM waits; `e0` counts forwarded operands. Subtract the
snapshots immediately before and after the formal kernel, excluding warmup
and the separate drain diagnostic. Never subtract across reset/flush.
The snapshots include small boundary overhead and are not atomic cycle
breakdowns. Default UART output is unchanged; these lines require the plusarg.

Compare formal `c` against 13,818 (first=0) and 12,576 (first=1), with the same
bin, input and completion boundary. `e0` must be nonzero before attributing any
speedup to forwarding. Numerical validation and difftest remain separate:
the current performance bin has `check=0`; rerun the correctness variant for
the RTL change. No NEMU or software-bin rebuild is required by this RTL patch.

## Independent reads and streaming reduction prefetch

After the E0-only change, the measured formal baseline is 13,818 cycles for
first=0 and 12,541 for first=1. The next RTL revision keeps depth=4 and the
software unchanged, and adds two changes:

1. Each execute operand may read once its own older writers have committed.
   A different source's RAW or destination WAR/WAW no longer delays that safe
   read. The existing operand registers retain it until all operands and issue
   protections are ready. Read reservations remain until the usual EG progress;
   early collection never lets younger writes overwrite unread operands.
   Gather/slide reads remain conservative. Final execution and writeback
   reservations still require all RAW/WAR/WAW and accumulator conditions.
2. Unmasked, non-widening unordered FP sum/min/max reductions may request the
   next source EG when the current streaming micro-op fires. The request uses
   the existing synchronous collector and its single successor slot. It is
   suppressed for pending older writers, a destination alias at the same EG,
   the final input, masked/elementwise operations, accumulator merges and folds.
   No arithmetic steps, FP rounding order, reduction contexts, or SRAM ports
   change. If bank arbitration rejects a prefetch, demand read retries normally.

Two final PERF counters report whether these paths were exercised:

| Counter | Meaning |
| --- | --- |
| `evu_early_operand_reads` | Operand handshakes while another source or destination hazard still blocks final issue. |
| `evu_reduction_prefetch_hints` | Legal successor hints generated by streaming reductions, not accepted SRAM reads. |

`[EVU-EXE]` snapshots add `early` and `rpf` for these counters. For the FMA,
comparison/max and EXP2 issue paths, final PERF output also exposes
`evu_fp_fma_*`, `evu_fp_cmp_*` and `evu_fp_exp_*`. At a traced FENCE, three
`[EVU-FU]` lines carry the same cumulative counters:

| Field | Meaning |
| --- | --- |
| `issue` | Actual FU micro-op issue cycles. |
| `dep/operand/feedback/wb/fu` | Primary waits across that FU's active sequencers. |
| `queue` | An issue-queue head cannot enter its eligible sequencer. |
| `dispatch` | A relevant full issue queue blocks the shared dispatch head. |

Both queue counters can overlap execution waits. Multiple contexts can also
wait while another context issues; these are not an exclusive utilization
breakdown. They are intended to distinguish an input-supply problem from
an occupied ordinary sequencer before adding contexts or arithmetic units.
The `-- +evu_mem_trace=1` option is required for formal-interval differences;
the guest binary's existing synchronization supplies the sampling boundaries.
Do not change the measured software or add UART prints inside its timed region.

## Demand priority and bounded critical-result arbitration (2026-11-02)

The latest independent-read plus reduction-prefetch run measured 13,959 / 12,609
formal cycles, versus 13,818 / 12,541 before those two changes. This is a small
regression, despite lower operand waits. Neither change added data registers,
collectors, SRAM ports, or arithmetic units: independent reads reuse captured
operands, and reduction hints reuse the existing successor collector. They are
retained while their arbitration is tightened; no area percentage is claimed
without synthesis. The E0 mirror is a separate earlier change.
The execute path now directly indexes the relevant EG dependency bits for
RAW/WAR/WAW and successor checks instead of explicitly expanding full-width
one-hot masks for those checks. Hazard semantics remain unchanged.

Demand reads now outrank speculative reads at both levels of the VRF crossbar.
Within each class the existing oldest-then-round-robin policy remains. Equal-row
sharing retains the strongest demand/age priority, so a speculative leader
cannot hide a demand from the physical-bank arbiter. A collector also prefers
an unfulfilled current request to a simultaneous successor hint. A hint that
loses arbitration is retried as an ordinary demand when its EG is needed; no
additional buffering or physical SRAM ports are introduced. Each bank remains
1RW and actual writes retain their existing priority over reads.

FMA and CMP use bounded priority for ready FP reduction merge/fold operations
and effective FP32 E0 operations. Streaming reductions are not promoted. Up to
three priority grants may occur while regular work is ready, then one regular
grant is required. Separate round-robin index selectors preserve fairness
within both classes; only one wide operand mux is used. Downstream stalls do
not consume the grant budget, and occupied future result slots remain protected.
The sequencer count, arithmetic width, dependency rules and memory depth=4 stay
unchanged. There is no evidence yet to add another ordinary execute context.

The following new counters are generated only when the internal
`VectorParams.diagnosticCounters` flag is true. Production EVU connects that flag
to `enableDifftest`; `EvuAreaMain` sets difftest false, so these counter registers
are absent even before synthesis. No public CONFIG, guest CSR, or ISA is added.

| Snapshot field | Meaning |
| --- | --- |
| FU `idle` | An active sequencer or nonempty issue queue exists, but the FU issues no micro-op. Includes dependency/input waits. |
| FU `arb` | At least two issue/result-slot eligible candidates exist and one is accepted; another loses arbitration. Can overlap useful execution. |
| FU `qi` | The issue-queue head cannot enter a sequencer and the FU issues nothing. Does not prove the queued work is independent. |
| FU `slot` | An otherwise requesting sequencer cannot reserve the shared future FU result slot. |
| FU `pri` | Accepted reduction-finishing or E0 priority micro-ops. |
| EXE `dpw` | A wrong outstanding speculative read blocks a demand, or a physical prefetch overtakes a different-row demand. The latter is asserted impossible. |
| EXE `defer` | A physical demand read is accepted while a different-row prefetch waits at that bank; counted once per cycle across banks. |
| EXE `used` | Accepted speculative responses consumed by matching demands; counted per collector consumption. |
| EXE `drop` | Speculative responses discarded by collectors; counted per discarded response, excluding reset. |

`used` and `drop` are events, not saved/lost cycle counts. Likewise `arb` is not
an idle-cycle counter, and `idle` does not imply every pipeline stage is empty.
Do not add overlapping waits or use whole-program totals
to estimate formal-kernel utilization.

Keep the existing guest bin and NEMU library. Rebuild one emu using the usual
command, then enable snapshots for the run:

```sh
./build/emu --diff /path/to/riscv64-nemu-interpreter-so \
  -C 100000000 -i software/evu/build/online_softmax_evu_test-riscv64-xs.bin \
  -- +evu_mem_trace=1 2>&1 | tee evu_softmax.log
python3 software/evu/scripts/summarize_softmax_fences.py evu_softmax.log
```

The helper subtracts the existing FENCE snapshots around the formal invocation,
using the header's warmup and diagnostic flags. With w=1 and diag=1, each case
has seven snapshots: initial reset, warmup end, post-warmup reset, formal end,
diagnostic reset, diagnostic end and empty drain. It subtracts the third from
the fourth; it does not average the three invocations. Both first=0 and first=1
are reported separately. Missing/incomplete snapshots fail explicitly instead
of guessing a boundary. The reported `fence_cycles` includes boundary/probe
overhead and is not a replacement for the guest's formal `c` measurement.

The helper runs on the host after simulation and adds no UART output or guest
instructions. Snapshot printing remains opt-in; software and its timing
boundaries are unchanged.

Validation for this revision:

- Full `xiangshan.compile` and `xiangshan.test.compile` passed.
- Twenty focused tests passed across read/execute priority, synchronous VRF,
  independent operands, E0 forwarding, write completion, and shared-FMA
  interleaving. The existing opt-in full-backend Treadle test was not enabled.
- Isolated Verilator EXP2 and reduction suites passed. Reduction reproduced
  `sum=0x4200d92a`, with maximum EXP2 relative error
  `2.0120961444454723E-7` in that reproduction.
- Final area/operand/priority recheck passed all 13 tests after narrowing the
  successor dependency index and suppressing out-of-range hints. Area CHIRRTL
  retained production SRAM and contained none of the new diagnostic registers.
- Four host-parser tests passed, including formal/warmup/diagnostic separation
  and explicit rejection of missing snapshots.

Local logs are `/tmp/evu-demand-priority-final-core-compile.log`,
`/tmp/evu-demand-priority-final-tests.log`,
`/tmp/evu-demand-priority-final-structure.log`,
`/tmp/evu-demand-priority-exp2.log`, and
`/tmp/evu-demand-priority-reduction.log`. Broad suites were rerun in isolated
processes where needed because the SRAM definition cache is JVM-global.
The EXP2 build initially needed writable compiler-cache access; the later
reduction run used `CCACHE_DIR=/tmp/evu-priority-ccache`.

No whole-system emu or guest bin was built or run locally for this revision.
Whole-operator cycle improvements and standard-cell area remain to be measured.
