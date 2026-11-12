# Paired-row optimization progress

Date: 2026-11-09. Plan: EVU_PAIRED_DEQUANT_PLAN.md.

| Phase | Source | Checks |
| --- | --- | --- |
| One ordinary FMA context / two bridge slots | Complete | Source review; server pending |
| Paired scales / row flags / dual-fragment stores | Complete | Tests added; server pending |
| Matrix backpressure diagnostics | Complete | Parser tests pass; server pending |
| VRF demand arbitration / EXP2 hint / tail diagnostics | Complete | Tests added; server pending |

All heavy validation is reserved for the server by user instruction.

Changed one paired difftest event instead of instantiating two unindexed events:
the checker invokes the existing NEMU row callback twice and preserves separate
flags and last semantics. No NEMU callback/guest ABI change is needed.

Added direct bridge capacity/replacement, paired scales and flags, parallel and
same-line fallback stores, end-to-end paired matrix pipeline, demand fairness
and dependent/independent EXP2 regressions. Updated all read-request producers
and test harnesses for lastOperand and optional diagnostics. Restored area
expectations to three FMA sequencers and the same physical 32 SRAM banks.

Final source review added a bridge flush/restart regression, a paired unaligned
four-fragment/four-Ack regression and a paired second-row fault-address/drain
regression. An elaboration invariant rejects odd tile dimensions when row
pairs are enabled. These Scala regressions remain uncompiled and unexecuted.

24 Python summary tests pass. git diff --check passes in main, CUTE and difftest.
The existing baseline log still parses to 6624/5437 kernel cycles.
Scala tests are source-only and have not been compiled or executed. Server
correctness, numerical comparisons, cycles, PPA and timing remain unverified.
See EVU_PAIRED_DEQUANT.md for precise counter units and server commands.

## Server compile failure: diagnostic Bundle member name

The server build in xsai_build.log stopped during xiangshan.compile:
EvuMatrixDiagnostics.scala:22 declared `elements: UInt`, conflicting with
Chisel Bundle.elements, whose type is SeqMap[String, Data]. CUTE and difftest
compiled before this failure; RTL generation and the emu link were not reached.

Renamed the counter to activeElements and updated its increment, FENCE output
and per-task delta references. The external log key remains elements, so the
parser and reference interfaces are unchanged. Local validation remains source
checks and Python tests only; the server Scala rebuild is still required.

## FlashAttention timeout after two matrix tasks

The November 9 17:45:24 server emu compiled, but its cold FlashAttention run
hit the 120000-cycle no-commit watchdog. The matched disassembly identifies
0x80001010 (msetcfg mcfg0,a0) before PV chunk 1, not the older 0x80000f9a store
shown in ABORT. 4096 row fragments and 1024 CUTE writes passed comparison;
there is no final performance or whole-workload correctness result.

Comparison with f21d9be9d found a pre-existing missing Mcfg writeback flush
signal. The decoded instruction requires serialization, but its FU did not
carry that signal to ExceptionGen. The November 10 correction enables and
connects the flag for legal MSETCFG, adds a production-Mcfg/ExceptionGen source
regression and a simulation-only long-head-stall report. The source defect is
confirmed; its causal role in this server timeout still requires a rerun.
See FLASH_ATTENTION_MCFG_TIMEOUT.md for checkpoint differences and commands.

Local post-fix checks: git diff --check and 24 Python summary tests passed.
No Java/Mill, Scala test execution, RTL generation, emu or bin build was run.

## Second timeout: shared CPU dispatch handshake

The November 10 10:48:50 emu still times out at the identical PC/cycle. The new
diagnostic reports wb=0, record_match=1, record_flush=1 and evu_busy=0. This rules
out the first missing-flush-record explanation for this run; it is not a reported
paired-dequantization value mismatch.

Source analysis found non-conserving handshakes in the shared EXU dispatcher:
the selected FU receives valid, but all FUs had to be ready for upstream success.
An inactive CSR also interpreted MSYNCRESET's opcode as MINIT and waited for an
EVU drain certificate without requesting one. The ABI2 change from CSR EVCFG
configuration to SETVLI can expose this because SETVLI invalidates the certificate.

Corrected selected-FU readiness and qualified CSR's drain guard with CSR valid;
real CSR drain/serialization checks remain intact. Added production Dispatcher
and BJU3 regressions, a handshake-conservation assertion, ROB uop-count reporting
and bounded optional BJU3 tracing. The exact failed-run replay history is not
known without an execution trace. No HBL2, CUTE, NEMU or guest-bin changes were
made in this follow-up. Server compile/tests and full FlashAttention remain
pending; do not mark this regression passed based on source checks alone.
