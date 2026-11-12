# FlashAttention matrix-configuration timeout

Date: 2026-11-10. The first correction did not resolve the server timeout.
The dispatch/CSR handshake correction below is implemented; validation pending.

## Second server run: execution handshake, not a missing flush record

The November 10 10:48:50 emu still stops at cycle 278129, instruction count
40687, with the same last committed instructions and checked event counts.
Its new diagnostic is decisive about which stage is blocked:

```text
[EVU-MCFG-STALL] pc=0x0000080001010 rob=308 wb=0 need_flush=1
  flushed=0 record=1 record_rob=308 record_match=1 record_flush=1
  evu_busy=0 can_flush=0 blocked=0 state=0
```

The head already has its matching flush record; it has not reached the ROB's
writeback-complete condition. EVU trap serialization is not holding it up.
The first hypothesis (completed MSETCFG waiting for a missing flush record)
does not explain this run. `wb=0` alone does not distinguish a missing execution
from duplicate writebacks that underflowed the uop counter. The ROB report now
also includes `uops` and `std_wb` to distinguish those cases.

### Source-level failure mechanism

BJU3 shares CSR, Fence, Div and Mcfg. The execution dispatcher previously sent
`valid` to the selected FU but returned the AND of **all** FUs' `ready` signals.
If a non-selected FU was not ready, the selected FU could consume/complete the
instruction while the upstream issue queue received a failed issue response
and retried it. This violates handshake conservation regardless of arithmetic.

The CSR wrapper provides a concrete trigger in this workload. All FUs see the
input payload, even when their `valid` is false. `MSYNCRESET` has internal
fuOpType `0b11000`: when interpreted in the CSR opcode namespace, bit 3 and
bits 1:0 make `isMinit` true. The inactive CSR consequently sees a fictitious
mstatus ownership change. Its old EVU ready guard did not qualify this by
CSR `valid`, so it returned not-ready whenever the drain certificate was absent.
However, `wantDrain` was correctly qualified by CSR `valid`, so this fictitious
request could never obtain a certificate itself. The selected Fence could
still accept MSYNCRESET despite the upstream failure response.

This connects to an actual checkpoint difference: the old FlashAttention
`config_e32` wrote EVCFG through CSR; the new bin uses ABI2 SETVLI. SETVLI retires
through AMU and invalidates the cached drain certificate in Backend.scala,
including when it immediately follows an explicit EVU FENCE. EVU busy becoming
zero does not recreate that certificate. Thus previously working timing or CSR
configuration sequences are not evidence that the old shared handshake was safe.
The failed bin reports `pv=1` (merged PV outputs). Its PV0 tail contains EVU
FENCE at 0x80001092 and SETVLI at 0x8000109e. The schedule skips the intermediate
output-update/FENCE between PV0 and PV1, so PV1 reaches MSYNCRESET at 0x80000fea
with no further explicit EVU FENCE after that SETVLI. This explains why the first
QK and PV0 matrix tasks can succeed before this particular control boundary fails.
No trace of the issue/execute handshakes was captured in the failed run, so the
exact replay sequence causing ROB 308 to stop is still a server-validation item.

### Current correction and coverage

- Dispatcher readiness comes only from the selected FU. An assertion requires
  input fire to equal the OR of output fires; the existing one-hot check remains.
- CSR's EVU guard is inactive when the CSR input is invalid. A real CSR config,
  ownership change or return still requires the original drain protection.
- The previous legal-MSETCFG writeback flush connection remains; no barrier,
  exception, scoreboard, Ack or difftest check is removed.
- McfgDispatchSpec exhausts all 128 valid/select/ready combinations with the
  production Dispatcher, then uses production BJU3 to test MSYNCRESET followed
  by MSETCFG without a drain certificate, real MINIT waiting for drain, and
  MGETCFG writeback backpressure. These Scala tests are not yet compiled/run.
- Optional `+evu_mcfg_trace=1` adds at most 128 BJU3 attempt/handshake reports.
  FU bit order in this configuration is CSR, Fence, Div, Mcfg (low to high).
  These diagnostics are generated only for difftest-enabled EVU configurations.

This is a shared CPU execution-interface correction, not an EVU arithmetic
change. It intentionally fixes the dispatcher protocol rather than weakening
matrix serialization. HBL2, CUTE, NEMU and the software bin are unchanged in
this follow-up. Full FlashAttention completion is still required before claiming
the server regression is resolved or publishing performance.

Local checks: `git diff --check` and the 24 existing Python summary tests pass.
A separate Boolean check enumerated 128 dispatcher input combinations: the old
equations have 28 input/output-fire mismatches; the corrected equations have
none. This is not a substitute for compiling/running the production Scala tests
or replaying the failing workload. No Java, RTL generation, emu or bin build was
run locally.

## First server run

The November 9 17:45:24 emu stops at cycle 278129 because no CPU instruction
committed for 120000 cycles. This is TimeoutChecker, not a reported arithmetic
or memory-value mismatch. The checked input is
flash_attention_evu_profile_cold-riscv64-xs.bin and its matching disassembly.

The last commit group starts at 0x8000100a and contains the address addition
and the li at 0x8000100c. The next instruction is 0x4205002b at 0x80001010,
msetcfg mcfg0,a0, with a0=2 (INT8). TimeoutChecker advances the reference by
one instruction, explaining REF.pc=0x80001014. The final ABORT.pc=0x80000f9a
is an older RVV store in the V-pack loop, not the first uncommitted instruction.

s2=0 and s3=1 identify key tile 0, PV chunk 1, inside matrix_shape(). The
checked_matrix_beats=4096 row fragments account for QK and PV0 (2048 each).
checked_cute_writes=1024 accounts for both 128x64 INT8 probability tiles at
16 elements per write. NEMU pending=0 does not prove the hardware busy flags
are clear. No final kernel timing or independent FP64 check was obtained.

## Checkpoint comparison

The latest local checkpoint tag is checkpoint/evu-upstream-deps-20261105,
commit f21d9be9d. Its dependency restoration is metadata-only relative to the
validated November 3 FlashAttention checkpoint 4ae2f00ff.

Changes since that checkpoint include:

- CPU address-preflight handoff and EVU memory completion/arbitration changes.
- ABI2 SETVL/SETVLI returning VL through a new scalar read/write connection;
  flash_attention_evu_test.c now configures through SETVLI rather than EVCFG.
- FMA context layout/diagnostics, demand-read aging and EXP2 lookahead.
- Paired matrix rows, two total bridge slots, two-row scales and store merging.
- CUTE has only an optional raw-result diagnostic probe added in this round.

The Mcfg unit, its decoder and ROB flush logic were unchanged before this fix.
Thus the missing writeback flush below is a latent defect, not evidence that
paired dequantization directly broke Mcfg. Changed instruction scheduling and
latency can expose the defect, but that causal path is not established by the
available log. Do not run an ABI2 bin on the old checkpoint as a matched A/B
test; its configuration instruction semantics differ.

## First correction: Mcfg writeback flush

MatrixDecoder marks MSETCFG blockBack=true and flushPipe=true. RobEnqData marks
its ROB entry needFlush. ROB then requires a matching ExceptionGen record before
publishing the flush and allowing retirement.

However, FuConfig.McfgCfg did not declare flushPipe, and Mcfg did not forward it.
The completed instruction therefore sent flushPipe=0 through ExeUnit's output
mux. This left the ROB dependent on the enqueue-time notification. ExceptionGen
keeps one oldest record, prioritizes writeback over enqueue, and filters enqueue
notifications during redirects/flush recovery. A missing enqueue notification
could not be reconstructed by Mcfg writeback, leaving a completed needFlush head
unable to retire and its backward barrier preventing younger progress.

McfgCfg now carries flushPipe. Mcfg republishes the decoded flag for legal
MSETCFG at writeback; MGETCFG does not flush and an unsupported MSETCFG reports
illegal-instruction instead. Existing configuration serialization is retained.
No EVU arithmetic, CUTE functionality, cache protocol, guest ISA or NEMU change
is part of this correction.

This is a definite missing connection, but the second run above shows it was
not sufficient and disproves the missing-flush-record hypothesis for that run.

## Regression and server checks

McfgSerializationSpec uses the production Mcfg and ExceptionGen. It deliberately
omits the enqueue hint and checks recovery from legal writeback, backpressure,
MGETCFG, unsupported types and clearing on flush. The old implementation cannot
produce the expected writeback flush record. These Scala tests have not been
compiled or executed locally under the user's no-Java/no-RTL-generation rule.

Run on the server:

```sh
mill -i evuMmuCheck.test.testOnly \
  xiangshan.evucheck.McfgDispatchSpec xiangshan.evucheck.McfgSerializationSpec
make emu CONFIG=DefaultExternalVectorMatrixConfig \
  ACCELERATOR_CDC_ENABLE=0 EMU_THREADS=8 EMU_TRACE=fst -j16 \
  2>&1 | tee xsai_build.log
```

Reuse the current ABI2 bin and NEMU from the failed run. Require GOOD TRAP,
no mismatch/timeout, all six matrix tasks (12288 checked row fragments), and
pending=0. The cold profile skips independent FP64 checking, so also run the
correctness/warm workload before recording performance.

For the follow-up run, retain the bounded dispatch trace:

```sh
./build/emu \
  --diff /nfs/home/guoqihang/workspace/NEMU_evu/build/riscv64-nemu-interpreter-so \
  -C 100000000 \
  -i software/evu/build/flash_attention_evu_profile_cold-riscv64-xs.bin \
  -- +evu_mcfg_trace=1 2>&1 | tee flash_attention_break_cold_evu.log
```

If the configuration remains stalled for 1024 head cycles, a simulation-only
[EVU-MCFG-STALL] line prints once for that head without needing a plusarg:

- wb=0, uops=1: the ROB has not counted the single expected writeback; inspect
  the BJU3 trace and issue queue. A large wrapped uops value instead points to
  duplicate writebacks; it must not be treated as just a slow FU.
- wb=1, need_flush=1, record_match=0: missing/mismatched flush record.
- evu_busy=1: inspect AMU outstanding entries and completion ownership.
- record_match=1, record_flush=1: inspect can_flush, flushed and blocked.

The diagnostic is generated only for difftest-enabled EVU configurations and
has no control outputs. It does not change the watchdog limit or weaken checks.
