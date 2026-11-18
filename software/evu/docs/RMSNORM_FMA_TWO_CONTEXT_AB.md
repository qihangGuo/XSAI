# Fused RMSNorm: two ordinary FMA contexts

## Change and baseline

The 2026-11-17 change sets `EvuParams.fmaOrdinaryContexts=2`. The default
`DefaultExternalVectorMatrixConfig` therefore has two ordinary FMA contexts
and two reduction contexts, sharing one existing FMA execution unit. The
control configuration `DefaultExternalVectorSingleFmaContextMatrixConfig`
sets only the ordinary-context count back to one. The common legacy
`VectorParams` default remains unchanged; the EVU wrapper explicitly supplies
the external-vector setting.

The additional context has its own sequencer and operand collectors. The
existing instruction queue admits its head into the first compatible available
context; it does not bypass an unadmitted queue head. Once admitted, the two
contexts can prepare operands independently. The existing shared arbiter issues
at most one micro-op per cycle and reserves the result slot. VAT age checks,
RAW/WAR/WAW hazards, collector invalidation and forwarding continue to protect
all contexts. FMA reduction owners move to context indices 2/3 through
`ExecuteContextLayout`; CMP owners stay at 1/2. No extra FMA arithmetic lanes,
VRF SRAM ports, reduction owners or guest instructions are introduced.

The immutable baseline is checkpoint `b4c28dbe5441789da54a10e02e525928e5cfec94`.
The baseline `rmsnorm_nvfp4_fused_evu_test.log` measured **one** ordinary FMA
context: 4909 cycles, 1684 CPU instructions, PASS/GOOD TRAP, 1612 checked
completions, 18 checked stores and no pending events. Its SHA-256 is
`046fb921956b5d534691334a016e5750f27104aef479f5e2e83470567a250cd9`.
That baseline is not evidence of the performance or numerical correctness of
the two-context configuration. The log has since been replaced by the combined
4183-cycle server run recorded in
[EVU_CHECKPOINT_2026-11-18.md](EVU_CHECKPOINT_2026-11-18.md); that run changes
both ordinary contexts and dispatch buffering and is not an isolated FMA A/B.

## Twenty useful counters

All values below are from that baseline log. Event counts have exact static
expectations for the current guest and geometry. Wait counters depend on issue
timing, producer latency, request overlap and arbitration, so a zero-contention
ideal must not be reported as an achievable zero-wait cycle prediction.

| # | Counter (omit common `evu_` prefix) | Theory or constraint | Actual | Interpretation |
| --- | --- | --- | ---: | --- |
| 1 | `amu_completion_fire` | Exactly 1612 guest commands | 1612 | Matches; includes two commands outside timing. |
| 2 | `fp_fma_issue` | Exactly 684 EG micro-ops | 684 | Matches; includes add, multiply and sum-reduction work. |
| 3 | `fp_cmp_issue` | 128 fabs + 128 amax + 16 scale max = 272 | 272 | Matches the single-EG direct reduction path. |
| 4 | `div_issue_cycles` | 8 batches x 3 divisions + 4 sqrt + 4 reciprocal = 32 | 32 | Counts issues, not divider busy cycles. |
| 5 | `fp_xx8_issue` | 8 scale encodes + 8 decodes = 16 | 16 | Matches; each issue handles 16 scales. |
| 6 | `fp_mxfp_issue` | 2048 input elements / 16 = 128 | 128 | Matches one FP4 conversion per block. |
| 7 | `memory_gets` | 160 vector beats + 11 E0 constant reads = 171 | 171 | Matches current physical EVU request generation; not DRAM reads. |
| 8 | `memory_puts` | (1024 packed bytes + 128 scale bytes) / 64 = 18 | 18 | Matches full-line outputs, no intermediate normalized stores. |
| 9 | `amu_completion_blocked` | Zero if reception never backpressures a valid completion | 0 | This reception boundary is not currently blocking. |
| 10 | `amu_issue_window_blocked` | Timing-dependent; ideal ready path has zero stalls | 2325 | A committed AMU window has a request but no fire; not an isolated completion-stall counter. |
| 11 | `job_full_cycles` | Timing-dependent; zero with sufficient live-command space | 450 | Accepted jobs outlive available slots; execution/order latency can cause this even with a ready completion sink. |
| 12 | `fp_fma_queue_wait` | Timing-dependent; zero only when every queue head can enter a compatible context | 651 | Context occupancy prevents admission; includes cycles while useful FMA work proceeds. |
| 13 | `fp_fma_queue_idle` | Zero under ideal admission and operand scheduling; not guaranteed | 222 | Queue head is blocked and FMA issues nothing; subset of row 12, the key two-context A/B target. |
| 14 | `fp_fma_operand_wait` | Depends on operand-read and collection timing, not static instruction count | 293 | Active FMA contexts lack operands after higher-priority wait reasons are excluded. |
| 15 | `fp_cmp_feedback_wait` | Depends on seed/accumulator readiness, not only tree depth | 234 | Includes initial seed preparation; not evidence that the removed streaming step still executes. |
| 16 | `vrf_bank_conflict_cycles` | Zero only when concurrent distinct-row reads avoid the same bank | 60 | Residual same-bank distinct-row read contention. |
| 17 | `vrf_write_blocks_read_cycles` | Zero only when reading and writing never compete for a 1RW bank | 314 | Writes occupy banks with pending reads; includes activity beyond the critical consumer. |
| 18 | `vrf_read_grant_wait_cycles` | Zero if all demand requests obtain immediate SRAM grants | 166 | Union of demand grant waits, not a sum of rows 16 and 17. |
| 19 | `vrf_read_response_wait_cycles` | No fixed count; synchronous SRAM has nonzero return latency | 1352 | Includes normal read latency and response collection; not all avoidable stalls. |
| 20 | `execute_dependency_wait_cycles` | Depends on real producer/consumer dependencies and visibility | 586 | Source/destination hazard checks block issue; another context may hide but cannot erase true dependencies. |

For row 2, the exact micro-op decomposition is:

```text
128  input-square multiplications (16 chunks x 8 EG)
 96  elementwise sum accumulation (4 rows x 3 later chunks x 8 EG)
 60  four sum reductions (8 streaming + 3 slot merges + 4 folds each)
  8  four mean multiplies and four epsilon adds
256  normalization multiplies (16 chunks x 8 EG x 2)
  8  eight scale-alpha multiplies
128  packing input multiplies (128 blocks)
---
684
```

Quantization contains 1504 commands after removal of the zero-offset slides;
the remaining fused/setup/drain commands contribute 108, giving row 1's 1612.
The initial SETVLI and preparation FENCE are outside timing; 1610 commands
belong to the measured call and its final drain. Rows 7/8 use a 64-byte memory
beat: 8192 input bytes plus 2048 gamma bytes require 160 beats, followed by four
RMSNorm and seven quantization E0 constant reads. FP4 packing writes 1024 bytes
and its 128 block scales write 128 bytes.

The guest executes only one fused sample, and the oracle does not replay EVU
arithmetic, so arithmetic events belong to that one workload. These are still
end-of-guest totals: no hardware FENCE snapshots exist in the baseline log.
Waits are not exact measurement-window deltas; reasons overlap between
contexts/subsystems, and one sequencer's primary-reason priority can move time
from one category to another without changing elapsed time. Never sum these
rows into lost cycles or predict that row 13 is a guaranteed 222-cycle saving.

## Server A/B

Build emulators on the server with the same options apart from `CONFIG`:

- Candidate: `DefaultExternalVectorMatrixConfig`.
- Control: `DefaultExternalVectorSingleFmaContextMatrixConfig`.

Reuse the same current fused-only guest binary and NEMU library. This scheduling
change requires no guest rebuild. Preserve each emulator separately so the A/B
does not accidentally run the same build twice. Run each with:

```sh
./build/emu --diff ../NEMU_evu/build/riscv64-nemu-interpreter-so \
  -C 100000000 -i software/evu/build/rmsnorm_nvfp4_fused_evu_test-riscv64-xs.bin \
  -- +evu_mem_trace=1 > rmsnorm-fused-two-fma.log 2>&1
/usr/bin/python3 software/evu/scripts/summarize_rmsnorm_counters.py \
  rmsnorm-fused-two-fma.log
```

Use a distinct log for the control. `[EVU-CONFIG]` must show `fma_contexts=2`
versus `1`, with the same other geometry and reduction count. Check output,
flags, Difftest completion/store counts and no pending events first. The event
rows above should remain exact for both builds. Accept a performance claim only
if measured fused cycles improve; a fall in queue waits alone is insufficient,
and increased bank pressure can offset any earlier operand preparation.

## Local validation scope

Targeted checks cover the default layout, both one/two-context reduction-owner
mappings, four-context arbitration fairness, critical-result priority under
backpressure and mixed-latency result reservations. They use pure Scala checks
or the Treadle interpreter: five tests passed. The production SRAM/CHIRRTL
structure test also passed, confirming context index 3 exists and index 4 does
not. Run that test with `src/main/resources` on PATH for the bundled Espresso
decoder minimizer. `mill -i evuMmuCheck.test.compile` passed for the main/core
and test sources. The full production-backend FMA execution test is guarded as
server-only and was cancelled by its environment guard, not executed locally.
No emulator is built or run, no Verilog is generated, and no guest binary is
built locally. Full numerical regression and cycle measurements of the
two-context candidate remain server validation tasks.
