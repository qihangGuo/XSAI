# Fused row-major profiling

`rmsnorm_nvfp4_fused_evu_test.c` runs only
`stage=fused_nvfp4 schedule=row_major`: four FP32 rows of 512 elements, RMSNorm
and NVFP4, including input loads and the final 1,152 output bytes. No empty,
standalone RMSNorm/quantization or wave4 case runs. The source includes the
existing format-11 kernels with their original entry point excluded. Arithmetic,
packing order and storage boundaries stay shared. Only this entry enables
`EVU_RMS_QUANT_DIRECT_FIRST=1`: block-zero fabs/multiply read the original input
instead of a temporary copy, removing 32 zero-offset slidedown instructions.
Temporary tails stay undisturbed and are not newly consumed. The original
11-case test is unchanged when built normally.

## Timing and validation

The default is one sample, no warmup. Every sample prints `[RN-SAMPLE]` with
its two adjacent hardware FENCE indices, even with `EVU_RMS_VERBOSE=0`.
Timing retains the old probes: after prepare/drain, read instret and cycle;
execute the fused kernel; drain EVU and scalar memory; read cycle and instret.
The end FENCE is included; no empty-case subtraction is made.

The checker does not replay RMSNorm or issue extra EVU arithmetic/stores.
CPU FP32 arithmetic reproduces the **DLEN=512** four round-robin accumulator
slots, slot merges and upper-half folds, then sqrt/divide, normalization and
the existing NVFP4 reference helpers. Both scale bytes and packed FP4 bytes
are compared exactly; buffer guards and error flags are checked. This oracle
is for the current DLEN=512 hardware, not a different-width EVU. Keep
`-fno-fast-math -ffp-contract=off` and disable CPU auto-vectorization, as the
Makefile does. Both lane-sum settings and BF16-rounded FP32 inputs are supported.

CPU initialization/checking and configuration/CSR traffic remain outside the
measured interval. Whole-guest EVU arithmetic counters now refer only to the
chosen fused calls, but cycle/no-work/memory counters still need FENCE deltas.
Each eligible call should contain 128 amax instructions and comparison-FU issue
count 272 with direct min/max, versus 400 with it disabled. Do not expect this
internal issue reduction to translate proportionally into kernel cycles.

With the default lane-sum setting, whole-guest EVU completion count is 1,612
with direct-first extraction and 1,644 with it disabled. Two of those commands
(initial SETVLI and preparation FENCE) are outside the timed interval. FMA/CMP
issues remain 684/272, FP8/FP4 issues remain 16/128, DIV issues remain 32, and
memory gets/puts remain 171/18. These expectations assume one sample and the
default hardware paths; they are instruction/request counts, not cycle targets.

The first sample has not run the other ten cases or the old checker's EVU
replay, so its cache/VRF state and code layout differ from the 11-case log.
Do **not** directly compare its cycles against the historical 5009. Run this
identical new image on both emulators. If using three samples, compare sample
0 separately from later samples; no explicit warmup does not make all samples cold.

## Server commands

Build on the server only, with `AM_HOME` configured:

```sh
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=rmsnorm_nvfp4_fused_evu_test
./build/emu --diff ../NEMU_evu/build/riscv64-nemu-interpreter-so \
  -C 100000000 -i software/evu/build/rmsnorm_nvfp4_fused_evu_test-riscv64-xs.bin \
  -- +evu_mem_trace=1 > rmsnorm-fused.log 2>&1
python3 software/evu/scripts/summarize_rmsnorm_counters.py rmsnorm-fused.log
```

For repeated measurements, add `EVU_RMS_SAMPLES=3` to the build command. The
Makefile gives nondefault options their own object and binary names. Use the
printed variant path rather than the default one when running that image.
The profiling header is `cases=1 format=11 profile=fused_row_major oracle_dlen=512`;
both existing cycle and counter summarizers accept it. Counter CSV includes
FMA/CMP issue, dependency/operand/feedback, queue-idle and result
slot waits when the emulator emits those fields. Missing optional fields stay
empty, not zero.

For a controlled hardware comparison, enable direct min/max with
`DefaultExternalVectorMatrixConfig`; disable only that feature with
`DefaultExternalVectorNoSingleGroupMinMaxMatrixConfig`. Keep the same guest,
NEMU, seed and other emulator options. Require PASS, GOOD TRAP, pending=0 and
the expected 1,152 logical output bytes before interpreting wait counters.

## Completion and extraction A/B

The default hardware also enables `EvuParams.completionLookahead`. AMU completion
reception targets the first unfinished entry after the already-completed prefix
being cleared that cycle. It cannot skip an unfinished/unaccepted entry, consume
the same completion twice, or accept a completion during redirect. Difftest PC
and instruction attribution use that same target. Status/flags and FENCE drain
protection retain their existing pipeline and ordering.

Use `DefaultExternalVectorNoCompletionLookaheadMatrixConfig` to disable only this
change. With lookahead disabled, reception retains the old every-other-cycle
bubble; with it enabled, ready consecutive entries can complete every cycle.
The default configuration remains `DefaultExternalVectorMatrixConfig`.

Four new end-of-guest performance counters are:

- `evu_amu_completion_fire`: accepted completions, expected 1,612 or 1,644.
- `evu_amu_completion_blocked`: completion valid while AMU is not ready, including
  redirects and genuinely unaccepted targets, not just the old dequeue bubble.
- `evu_amu_completion_lookahead`: accepted completion targeting beyond the old
  dequeue pointer; zero with the feature disabled.
- `evu_amu_completion_consecutive`: accepted in consecutive cycles; zero with
  the feature disabled, potentially nonzero with it enabled.

Build the software baseline on the server with
`NAME=rmsnorm_nvfp4_fused_evu_test EVU_RMS_QUANT_DIRECT_FIRST=0`. The Makefile uses
separate variant object directories and a suffixed baseline image path; the
header prints `quant_direct_first=0` or `1`. Compare the same software image on
both hardware configurations, then both software images on the same hardware.
Do not attribute a combined A/B result entirely to hardware. Require matching
output bytes and the expected issue counts; then compare cycles, completion
blocking, `job_full`, AMU issue-window blocking and preflight serial stalls.

Local validation uses Scala compilation, Treadle-only AMU tests, RISC-V
syntax/preprocessor checks and Python source/parser regressions. A symbolic
byte-dependency test compares all 18 output stores, including poisoned temporary
tails, for all bank/rename settings. It does not execute the guest or generate
Verilog, emu or a guest binary. Server Difftest/bit-exact execution and timing
remain required before claiming a performance improvement.

## Two ordinary FMA contexts

The default external-vector configuration now uses two ordinary FMA contexts
and two reduction contexts sharing the existing single FMA arithmetic path.
`DefaultExternalVectorSingleFmaContextMatrixConfig` retains one ordinary context
for an isolated A/B. CMP retains one ordinary context and two reduction contexts.
Arithmetic width, reduction-owner count, ISA and guest scheduling are unchanged.
Legacy non-interleaved backend configurations retain their existing layout.

Reuse the exact same fused-only guest image and NEMU library on both emulator
builds. With `-- +evu_mem_trace=1`, `[EVU-CONFIG]` must report `fma_contexts=2`
for the default and `fma_contexts=1` for the control. The baseline 4909-cycle
log measured one ordinary context; it is not a measurement of the new default.
Require unchanged correctness and work counts, then compare elapsed cycles,
FMA queue-idle/operand waits and VRF contention. An extra context can overlap
independent preparation, but does not eliminate true dependencies or add SRAM
ports. Baseline counter definitions and arithmetic derivations are in
[RMSNORM_FMA_TWO_CONTEXT_AB.md](RMSNORM_FMA_TWO_CONTEXT_AB.md).

## Dispatch buffering

`EvuDispatchAdapter` now buffers two commands with `pipe=false, flow=false`.
With a continuous producer and ready consumer, it accepts and forwards one
command each cycle after the initial buffering cycle. The old one-entry queue
alternated enqueue and dequeue. Downstream readiness is not combinationally
propagated through the queue to the producer. A completely full queue still
waits until the cycle after a dequeue before accepting another command.

Treadle checks cover a 64-command continuous stream with distinct PC,
configuration and page-map payloads, two-command capacity under backpressure,
recovery without loss/duplication, and flushing both entries including input
offered while flush remains asserted. No software or ISA change is needed.
The default candidate also includes the two ordinary FMA contexts above;
the single-FMA control configuration changes only the FMA count and still uses
the new two-entry dispatch queue. The 4909-cycle checkpoint predates both
changes, so a combined comparison cannot attribute the cycle delta entirely
to either one. The subsequent combined server run passes Difftest at 4183 cycles;
its log hash and counter evidence are recorded in
[EVU_CHECKPOINT_2026-11-18.md](EVU_CHECKPOINT_2026-11-18.md). Isolated A/B and
hardware FENCE snapshots remain required to attribute individual gains.
