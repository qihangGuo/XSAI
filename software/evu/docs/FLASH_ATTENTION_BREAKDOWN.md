# EVU Flash Attention Cycle Breakdown

`flash_attention_evu_breakdown.c` profiles the same Q128/KV256/D64 kernel,
inputs, independent FP64 goldens and tolerances as `flash_attention_evu_test.c`.
It includes that source with optional phase hooks and its own main. In the
original executable and native reference adapter the hooks expand to no-ops;
the original test still performs one warm-up and three timed runs. No hardware
or NEMU changes are needed for the phase hooks themselves. The current shared
kernel also uses scalar instructions (0x52..0x5e), so it requires the updated RTL and NEMU
described in `FLASH_ATTENTION.md`.

For a short cycle-only run without guest FP64 comparisons, use
`flash_attention_evu_profile_cold`. For correctness plus one warmed profile,
use `flash_attention_evu_correct_warm`: it computes the independent FP64
goldens, checks the warm-up and profiled outputs, and omits the hooks-disabled
baseline execution. The more verbose `flash_attention_evu_breakdown` retains
that extra baseline for measuring profiler overhead.

| Target | Full warm-up | Baseline run | Profiled run | Guest FP64 checks |
| --- | ---: | ---: | ---: | --- |
| `flash_attention_evu_breakdown` | 1 | 1 | 1 | Both goldens |
| `flash_attention_evu_correct_warm` | 1 | 0 | 1 | Both goldens |
| `flash_attention_evu_profile_cold` | 0 | 0 | 1 | None |

## Current Software Revision: Scalar Instructions and Batched PV Updates

The default `flash_attention_evu_profile_cold` now runs exactly one complete
Q128/KV256/D64 kernel with all three software changes: compact P scales/row
sums, paired PV output updates, four-row batches and instruction-local scalar
operations. It has no warm-up,
baseline run, guest FP64 reference or output-accuracy check. This revision
requires the matching new RTL/emu, NEMU reference and guest to be rebuilt
on the server. The previous 476634-cycle result used `compact_batched_v2`
before the scalar instruction change; the latest cold run is recorded below.

The expected banner is:

```text
kernel=scalar_batched_v3 profile_schema=compact_pv_pair_v2 row_batch=4 compact_state=1 pv_pair=1 output_passes=2 scalar_ops=1 scale_vl_switches=0 normalize_vl_switches=0
```

P scales occupy 1024 bytes instead of 65536, and row sums 512 bytes instead of
32768. Max keeps its legacy allocation, but only element zero is accessed by
the scalar path. The PV result allocation grows
from one 64 KiB buffer to two, allowing each key tile's two PV contributions
to update output in one pass. It retains both separate FP32 adds, the first
positive-zero add, second-tile alpha scaling, and final reciprocal/multiply.
There is no FMA substitution, no chunk-max shortcut, and no core RVV/FP work.

Each row still has independent sum/max reductions and 640 effective division
elements per kernel. Scalar instructions eliminate the remaining 320 scalar-work
VL switches without changing global VL. This is a source-level
operation count, not a measured cycle reduction. Paired output updates remove
128 KiB of logical output reads/writes. Smaller scalar stores still incur
line-level traffic; no 64-fold bus reduction is claimed.

`EVU_FLASH_ROW_BATCH=1/2/4`, `EVU_FLASH_MERGE_PV=0/1` and
`EVU_FLASH_SCALAR_OPS=0/1` are optional server build parameters. Compact state
is always enabled. Defaults are 4, 1 and 1. Scalar0 selects the previous
`compact_batched_v2` software path for a fixed-guest comparison of RTL batches
1-3; scalar1 uses batch 4 too. Nondefault variants get
`-rowsN-pvM-scalarS-` in their image names, and all variants have distinct
object directories, preventing a previously built schedule from being reused.
These are alternative builds, not multiple configurations executed in one run.

The paired schema collects 21 samples (one ordinary output update and one
normalized update). With PV pairing disabled, `compact_chunk_pv_v2` collects
23 (three ordinary updates and one normalized update). Compare the sum of both
output phases across schemas, since their call counts differ. The software
changes also remove two intermediate output drains; all remaining waits and
matrix-result visibility fences are retained.

Local validation includes RISC-V syntax-only checks of all four entrypoints.
A temporary Clang-AST source checker
also compares actual register schedules with the ordered FP32 expression graph
of row_fused_v1, treating matrix results and reductions as opaque deterministic
operations. All 12 row/PV/scalar configurations pass twice with initially undefined state;
quantization expressions, scales, sums, final outputs and scalar VL-switch
counts match the expected graphs. This check does not execute NEMU/RTL,
evaluate the hardware exp2 approximation, or measure cycles. Server difftest and measured
performance for the default configuration are recorded below, including the
completed independent FP64 checks and warmed run. See the roadmap for the RTL
validation record and the failed legacy Verilator invocation; the syntax and
AST checks themselves produce no executable, object or Verilog.

### Latest Cold Server Result

`flash_attention_break_cold_evu.log` measures `scalar_batched_v3`: the cold
profiled kernel took 323025 cycles and 34567 instructions. The 21 phase samples
account for 322688 cycles, with 337 outside the phase intervals.

| Phase | Cycles | Share |
| --- | ---: | ---: |
| Row softmax and quantize | 179072 | 55.43% |
| PV packing | 28454 | 8.80% |
| PV wait/drain | 20801 | 6.43% |
| Output accumulation | 35190 | 10.89% |
| Output accumulation and normalization | 45239 | 14.00% |
| Setup and QK phases | 11939 | 3.70% |

The log reports `profile_errors=0`, `failures=0`, `evstatus=0`, `efflags=0x1`
and `fp64_checked=0`. Difftest ended with `HIT GOOD TRAP`, 8704 checked stores,
9514 completions, 12288 matrix beats, 4096 CUTE writes and `pending=0`. This
confirms instruction/model agreement and completion for this run, but not
independent FP64 attention accuracy.

This cold EVU kernel is 32.23% below the prior 476634-cycle EVU result. Against
the historical RVV figure of 322302 cycles it is 723 cycles (0.224%) slower,
or 1.00224x. Treat this as near parity, not a demonstrated speedup: the RVV
measurement is historical and the cold/warm conditions are not matched. The
next run should use `flash_attention_evu_correct_warm`, followed by a warmed
RVV run with the same inputs, emulator configuration and timing boundaries.

### Warm Correctness Checkpoint: 2026-10-28

The supplied `flash_attention_correct_warm.log` completes the recommended
two-pass test. The first kernel takes 321978 cycles; the warmed profiled kernel
takes **300289 cycles** and 34700 instructions. Both outputs pass the unchanged
FP64 checks. The log ends with `fp64_checked=1`, `warmup_checked=1`,
`failures=0`, GOOD TRAP and `pending=0`; all 21 phase samples validate.

Against the recorded warmed RVV LMUL8 result of 322302 cycles, the warmed EVU
kernel takes 22013 fewer cycles (6.83%), a 1.073306x speedup for the complete
software/hardware implementation on the same operator and fixed input.
Softmax/quantization takes 172328 cycles (57.38%); the two output phases take
75929 cycles (25.29%). The preceding cold comparison remains a separate
historical measurement. Version pairing, detailed results, log digests and
reproduction commands are in the [checkpoint record](checkpoints/2026-10-28-flash-warm-300289.md).

## Execution and timing

The verbose diagnostic constructs the FP64 reference once, then executes:

1. One full warm-up, with output and EVU status checks.
2. One baseline kernel with phase hooks disabled, with the same checks.
3. One kernel with phase hooks enabled, followed by the same checks and reports.

Three full kernels instead of four reduce the server run time. All printing,
reference construction and output checking is outside the kernel timer.
Progress messages precede each full kernel; no per-row or in-kernel printing
is performed. `validation_cycles` measures `check_output`, including its
single existing summary print, separately from the kernel.
Performance-only mode has no output-accuracy calculation and reports
`validation_cycles=0`; EVU status and FP flags are still checked.

The performance-only targets construct the same complete Q/K/V inputs and
constants, then execute only the runs listed above. They retain EVU error and
FP-flag checks and the same phase accounting checks. Run them with the same
`--diff` argument to retain NEMU comparisons of architectural instructions,
stores, completions, matrix beats and CUTE writes. Difftest checks the hardware
against its model; these modes do not independently check attention accuracy.
Their `result=PASS` therefore applies to status and profiling checks, with
`fp64_checked=0` printed explicitly.

Performance-only reports print the nine phase aggregates and total accounting,
omitting individual-sample and duplicate group lines to reduce guest printing.
All configured intervals (21 by default, 23 without PV pairing) are collected
and checked. There is no baseline run or
`profile_minus_baseline_cycles` report. Removing reference work and output
checks saves simulation time outside the kernel; it does not directly reduce
the reported kernel cycles. Different code layout and cache state can still
cause cycle variation between executables.

`flash_attention_evu_correct_warm` uses one warm-up followed by one measured
profile pass, validating both outputs against the FP64 goldens. The `cold`
target reports `timing_state=no_warmup`: input initialization has
already run, but the full attention kernel has not. Its cycles are useful for
fast triage, and should be compared only with runs using the same warm-up
policy, not mixed with warmed measurements.

The profiler reads `rdcycle` and `rdinstret` at existing coarse boundaries.
No additional `XV_FENCE`, core fence, per-instruction waits or workload
reordering are added. Vector stages end after the existing EVU/core fences,
so their measured time includes command backpressure, execution and the last
write acknowledgement, not just the scalar instruction submission time.

## Phase definitions

The current `profile_schema=compact_pv_pair_v2` retains row-local max/alpha
and both softmax chunks, batches four rows, and combines the two PV output
updates per key tile. Final normalization stays in the last output pass. The
previous `row_fused_v1` schema has 23 samples; older logs have 28. Compare
`row_softmax_quantize` with old `max_alpha_update + softmax_quantize`;
compare the sum of both new output phases with old
`output_accumulate + final_normalize`. The software fusion removes phase
boundaries and their drains rather than adding per-row instrumentation.

| Phase | Calls | Included work |
| --- | ---: | --- |
| `setup_constants` | 1 | EVU configuration, constants and drain; output is initialized by the first PV pass |
| `qk_submit` | 2 | Matrix shape, EVU scale loads/launch, Q/K loads, MQMA and MRELEASE submission |
| `qk_wait_drain` | 2 | MACQUIRE, XV_WAIT, existing fences, switch back to VL64 |
| `row_softmax_quantize` | 2 | Four-row groups retain both probability chunks; instruction-local scalar operations update sums/scales without changing VL, compact state is stored, and each row is separately quantized to CUTE |
| `pv_pack` | 4 | V transpose/zero padding; P scales are already contiguous and need no scalar packing |
| `pv_submit` | 4 | Matrix shape, scales/launch, V load, MQMA and MRELEASE submission |
| `pv_wait_drain` | 4 | MACQUIRE, XV_WAIT, existing fences, switch back to VL64 |
| `output_accumulate` | 1 | First key tile consumes both PV buffers with two adds, including the first +0 add, without an old-output load; stores output once and drains |
| `output_accumulate_normalize` | 1 | Last key tile consumes both PV buffers, retains the running output in registers, computes compact-denominator reciprocals in four-row VL1 windows, then broadcasts/multiplies/stores and drains |

There are 21 samples by default. `tile=0/1` distinguishes first and second key
tiles; `chunk=0/1` distinguishes matrix work on the two 64-key chunks. Paired
output updates cover both chunks; their chunk metadata is `-1`, as for other
intervals without a single applicable chunk.
This allows first-tile initialization to be compared with second-tile online
rescaling, without averaging away their different work.

The submit/wait split is a software observation boundary, not a hardware
utilization measurement: matrix work may already execute during submission
or the profiler bookkeeping between the two intervals. Wait includes
remaining matrix work, dequantization, HBL2 writeback and synchronization.
Do not label `qk_wait_drain` or `pv_wait_drain` as pure matrix arithmetic time.
Likewise `pv_pack` can leave core stores in flight; the original pre-load
fence in `pv_submit` accounts for their remaining visibility latency.

## Reports and accounting

Output has the prefix `[EVU-FLASH-BREAKDOWN]`:

- `sample=... phase=... tile=... chunk=... cycles=... instret=... wall_pct=...`
  gives each individual interval.
- `phase=... calls=...` aggregates each of the nine phases.
- `group=...` combines QK submit/wait and PV pack/submit/wait for coarse ranking.
  Groups summarize the same samples; do not add groups to phase totals.
- `accounted_cycles` is the sum of the configured phase intervals.
- `outside_phases_cycles` is profiled wall time minus that sum, including
  loop/call control and profiling bookkeeping between phase timers.
- `profile_minus_baseline_cycles` and signed `delta_pct` compare the
  profiled run with the hooks-disabled run. They include cache/layout/order
  effects as well as instrumentation; they are not a pure timer calibration.

The hooks-disabled kernel still calls the disabled hooks and has a different
code layout from the original binary. Use it to assess instrumentation, not
as a replacement for the original three-run performance median. Do not
subtract a guessed fixed timer cost from every phase, or normalize phase
times to make them equal the original 2816182-cycle result.

Every `wall_pct` uses the profiled kernel wall-cycle denominator. Phase sums
plus `outside_phases_cycles` must equal `wall_cycles`; phase percentages may
sum below 100%. Sample counts and calls per phase are checked, and malformed
profiling state fails the test rather than silently dropping intervals.

For bottleneck triage, compare the largest aggregate groups first:

- Large QK/PV wait groups motivate matrix-stream/writeback/backpressure counters.
- Large `softmax_quantize` motivates a separate, controlled investigation of
  vector loads, reductions, division and FP quantization; this coarse test
  cannot distinguish their individual contributions.
- Large max/output/normalization groups motivate broadcast-state traffic and
  repeated division investigation.
- Large `pv_pack` points to scalar V/scale layout adaptation.

## Division optimization comparison

The server's `flash_attention_break_cold_evu.log` measures the division-only
software optimization, before immediate gather and VRF return bypass:

| Phase | Previous warmed profile | Division-only cold profile |
| --- | ---: | ---: |
| setup_reset | 31019 | 51208 |
| qk_submit | 900 | 1062 |
| qk_wait_drain | 91347 | 145974 |
| max_alpha_update | 267227 | 321575 |
| softmax_quantize | 1716464 | 908327 |
| pv_pack | 31141 | 30378 |
| pv_submit | 2114 | 2278 |
| pv_wait_drain | 182670 | 237018 |
| output_accumulate | 189355 | 189507 |
| final_normalize | 304309 | 82789 |
| Entire profiled kernel | 2816870 | 1970504 |

These are different cache/warm-up conditions, so the apparent 30.0% total,
47.1% softmax and 72.8% normalization reductions are descriptive comparisons,
not matched speedups. The earlier first kernel took 3077796 cycles, also
with a different executable and prior reference work. The new cold run is
6.11 times the historical 322302-cycle warmed RVV result. QK/PV wait totals
382992 cycles and includes matrix output traffic and synchronization.

The new run ended with GOOD TRAP, 28 samples, `profile_errors=0`,
`fp64_checked=0` and difftest pending=0. It checked 31744 stores, 14385
completions, 12288 matrix beats and 4096 CUTE writes. Total simulation was
2327898 cycles and 2202085 ms (36 min 42 s). Its old empty-validation timing
printed UINT64_MAX-1; performance-only mode now reports zero explicitly.
The phase sum plus 388 outside cycles equals the measured kernel wall time.

The existing `flash_attention_break_evu.log` predates the single-lane division
optimization and passed all checks. Its hooks-disabled kernel took 2816229
cycles; the profiled kernel took 2816870 cycles, with 28 samples and 324 cycles
outside phases. The two affected phase aggregates were:

| Phase | Previous cycles | Previous wall percentage |
| --- | ---: | ---: |
| `softmax_quantize` | 1716464 | 60.93% |
| `final_normalize` | 304309 | 10.80% |

The current shared kernel computes `127/max_exp` once per row/chunk with VL=1,
then restores VL=64 and broadcasts the ratio. Final normalization computes
`1/row_sum` once per row and broadcasts it before vector multiplication.
This reduces FP32 division element counts from 32768 to 512 and from 8192 to
128 per kernel respectively. The header prints
`division=single_lane_scale_reciprocal_broadcast broadcast=gather_imm scale_divisions=512 normalize_divisions=128`
to distinguish a rebuilt guest from the previous image.

All EVCFG writes and broadcasts stay inside the corresponding phase timers;
CSR draining and any changed command overlap are part of the measured cost.
In full-validation mode the existing FP64 references and limits also apply
to reciprocal multiply's different rounding. Compare phase aggregates and
total kernel cycles using the same executable and warm-up policy after
rerunning; division count reduction alone is not a cycle-speedup measurement.
Use the ordinary test's three-run median for final performance reporting.
The division change itself needs no RTL or NEMU edits. The subsequent
`broadcast=gather_imm` revision does; rebuild all three server artifacts.

## Server-only build and run

From the `XSAI_external_vector` root, use the server AM toolchain, an EMU with
`CONFIG=DefaultExternalVectorMatrixConfig`, and a matching `NEMU_evu` that
supports the current scalar, GatherImm and FP32-quantization instructions.
Rebuild emu, NEMU and guest from matching sources. The cycle-only cold run is:

```sh
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=flash_attention_evu_profile_cold
./build/emu \
  --diff /nfs/home/guoqihang/workspace/NEMU_evu/build/riscv64-nemu-interpreter-so \
  -C 100000000 \
  -i software/evu/build/flash_attention_evu_profile_cold-riscv64-xs.bin \
  2>&1 | tee flash_attention_break_cold_evu.log
```

For correctness and the warmed-cycle result, use the new two-pass target:

```sh
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=flash_attention_evu_correct_warm
./build/emu \
  --diff /nfs/home/guoqihang/workspace/NEMU_evu/build/riscv64-nemu-interpreter-so \
  -C 100000000 \
  -i software/evu/build/flash_attention_evu_correct_warm-riscv64-xs.bin \
  2>&1 | tee flash_attention_evu_correct_warm.log
```

Require `warmup=1 baseline_runs=0 profiled_runs=1 correctness=1`,
`fp64_checked=1`, `warmup_checked=1`, `failures=0`, `samples=21`, GOOD TRAP
and `pending=0`.

For the original full-validation breakdown:

```sh
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=flash_attention_evu_breakdown
./build/emu \
  --diff /nfs/home/guoqihang/workspace/NEMU_evu/build/riscv64-nemu-interpreter-so \
  -C 100000000 \
  -i software/evu/build/flash_attention_evu_breakdown-riscv64-xs.bin \
  2>&1 | tee flash_attention_evu_breakdown.log
```

Retain the same `AM_HOME`, compiler settings, hardware configuration, clocks
and cache settings between EVU and RVV comparisons. Current default software
uses scalar instructions in addition to GatherImm and FP quantization, so the
EMU, NEMU and guest sources must be a matching revision. The new test entry
does not change RTL, NEMU or capability bits: reuse the emu/reference pair
that passed the latest scalar_batched_v3 cold run and rebuild only the guest.

Acceptance for all modes is `result=PASS`, `profile_errors=0`, `samples=21`
(`23` with PV pairing disabled),
permitted EVU flags only, `HIT GOOD TRAP`, no difftest mismatch and EVU
`pending=0`. Full-validation mode additionally requires all three output
checks with `failures=0`. Expected event counts are:

| Target | Matrix beats | CUTE writes |
| --- | ---: | ---: |
| Full breakdown | 36864 | 12288 |
| Correctness + warmed profile | 24576 | 8192 |
| Single-run profile | 12288 | 4096 |

These are derived counts, not new server measurements. Performance-only
banners must show `fp64_reference=0 output_accuracy_check=0 baseline_runs=0`
(field order may differ), plus `warmup=1` or `warmup=0` for the chosen target.

Local validation of the division optimization covers no-output GCC/Clang
syntax checks and targeted instruction sequences in the existing synchronous
NEMU shared library: nine quantization-ratio inputs match the previous
division bit for bit, and 640 normalization values change by at most one ULP.
This does not execute the complete updated guest. No local EMU execution,
guest object/bin build or Verilog generation is part of this workflow. Full
FP64 acceptance and actual stage cycle numbers require the server run.
The cold performance target, original test and full breakdown also pass
GCC/Clang RISC-V no-output syntax checks. Preprocessing checks confirmed the
cold target excludes both FP64 oracles and their buffers. The new correctness
plus warmed target has not been run locally; its server run is the next
numerical validation step.

The historical row-fused revision is identified by `kernel=row_fused_v1` and
`profile_schema=row_fused_v1`. It retains the event counts in the table
above, not the old store/completion totals: redundant state/output memory
commands have been removed. Local RISC-V syntax checks cover all four
entrypoints, and the native adapter source is syntax-checked without
building or executing a binary. Full numerical and cycle validation of
this revision remain server-side work.
