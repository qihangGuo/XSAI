# EVU Tests

Run commands below from the XSAI repository root. These bare-metal tests target
`DefaultExternalVectorMatrixConfig`, with VLEN=4096 and DLEN=256. Build guest
programs on the server using its existing `AM_HOME` and LLVM toolchain.

## Directory

```text
software/evu/
  *_test.c, *_example.c  Bare-metal test entry points
  include/              EVU and AME instruction helpers
  host/                 Native host tests for the NEMU reference
  scripts/              Reference build/check helpers
  docs/                 Reference limitations and failure diagnostics
  build/                Generated binaries, disassembly and objects (ignored)
```

| Program (`NAME`) | Coverage |
| --- | --- |
| `vector_add_evu_test` | e8/e16/e32 addition, copies, source aliases, RAW dependencies and guards |
| `exp2_evu_test` | FP16/FP32 Exp2, exact powers of two, in-place writes and source preservation |
| `online_softmax_evu_test` | Pure FP32 online update, matched to RVV four-row positive-scale/vector-alpha kernel; timed and checked |
| `vector_memory_path_evu_test` | Standalone cache-state, bulk/state memory and register-resident compute diagnostics; no CUTE or FP64 |
| `quanti8mma_evu_example` | INT8 CUTE MMA followed by EVU FP32 matrix-stream dequantization and stores |
| `virtual_memory_evu_test` | S-mode Sv39 EVU memory access and matrix-stream output |

The C entry points and binary names are unchanged. Shared headers now live in
`include/`; the Makefile supplies the include path. Objects are isolated under
`build/obj/<NAME>/` so old dependency files do not refer to moved headers.
Existing generated files under `build/` are retained, but must not be treated
as results from the current sources.

## Build And Run

Select a program with `NAME`; do not pass `SRCS` on the make command line,
because Abstract Machine recursively builds its own libraries.

```sh
make -C software/evu git_commit= ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=vector_add_evu_test
set -o pipefail
./build/emu \
  --diff /nfs/home/guoqihang/workspace/NEMU_evu/build/riscv64-nemu-interpreter-so \
  -C 2000000 -i software/evu/build/vector_add_evu_test-riscv64-xs.bin \
  2>&1 | tee run_vector_add_evu.log
```

EVU Difftest requires a matching `CONFIG_CUSTOM_EVU=y` NEMU reference with
VLEN=4096/DLEN=256, and a common core/accelerator clock
(`ACCELERATOR_CDC_ENABLE=0`). Rebuild EMU after changing RTL. ADD passing does
not establish correctness of the FP or matrix paths.

The standalone ADD, Exp2 and online softmax tests disable compiler loop/SLP
vectorization and use volatile buffers, keeping initialization and numerical
checking on the scalar core. Each test returns 1 on failure. Require its final
PASS message and `HIT GOOD TRAP`; with Difftest, also require no mismatch and
`pending=0` in the final EVU counters.

For ADD, failure output indices mean copy (0), add (1), alias with source 1
(2), and alias with source 2 (3). The last result is `a + 2*b`; other sums are
`a + b`. For Exp2, require both FP16 and FP32 PASS messages. BF16 is disabled.

## Online Softmax

`online_softmax_evu_test.c` measures the same pure-vector online update as
`softmax_online_rvv_vfexp2_4row_lmul4_positive_scale_vector_alpha` in
`nexus-am/tests/exp2test/vendor/xsai_blas_rvv/`. Defaults are 32 rows, 256
columns, stride 256, positive scale 1.0, four-row interleaving and compact
FP32 row states. Input values and initial max/sum match the RVV comparison
test exactly. There is no CUTE command, quantization, final normalization,
or dedicated Softmax instruction.

```text
tile_max = max(scores) * scale
m_new = first ? tile_max : max(m_old, tile_max)
alpha = first ? 0 : exp2((m_old - m_new) * log2(e))
scores[i] = exp2(scores[i] * (scale * log2(e)) - m_new * log2(e))
l_new = alpha * l_old + sum(scores)
```

The EVU schedule is chosen for its own VRF and scalar-source instructions.
At the default 256 columns, four complete rows occupy eight vector
registers (4 KiB). Each score is loaded once and stays in the VRF through
max, exp and sum; the two chunks' pairwise max/sum replace repeated
accumulator initialization and updates. The default `wave4x2` schedule uses
two alternating eight-register input buffers: four active rows and up to four
prefetched rows (8 KiB of input). It advances two rows through max, state and
EXP2 before submitting the next pair of max reductions. Head EXP2 is submitted
before tail subtraction so the EXP2 and FMA paths can overlap. Next-batch
loads are mixed with current-batch work; they still obey VMU ordering and may
stall at the command head. This is scheduling opportunity, not eight-row
simultaneous computation or guaranteed hardware overlap.

States stay in element zero, using existing E0/scalar-source instructions.
The default has one entry configuration, no scratch packing, no gather and
no inner VL changes. In this RTL, EVCFG writes drain EVU; the old batched-state
candidate had 33 such configurations. Each row retains its multiply, exp and
sum arithmetic and rounding order. No FP contraction or new opcode is used.
The wider buffers use 16 score registers; state temporaries and constants use
another 15 architectural registers, leaving v26 unused. Larger VRF capacity
does not imply additional arithmetic units or simultaneous SRAM accesses.

The banner prints `sched=wave4x2 state=0 red=128` for the default schedule.
`EVU_SOFTMAX_PIPELINE=0` restores the old `resident_pair2` schedule.
`EVU_SOFTMAX_STATE_LOAD_BATCH=1` tests two 16-byte old-state loads per four
rows, followed by eight register gathers, without scratch-memory packing.
For default first=0 it reduces memory instructions from 293 to 245, but adds
16 draining EVCFG writes and 64 gathers. It stays opt-in until measured;
the default prints `loads4=0`, while this candidate prints `loads4=1`.
First=1 has no carried-state loads and does not add these configurations.
`EVU_SOFTMAX_STATE_BATCH=1` retains the scratch-packed VL=4 experiment;
`EVU_SOFTMAX_REDUCE_VL=64/32` retains short-reduction experiments. These
automatically disable the new pipeline and have separate object/image paths.
The default is a source-validated candidate, not a measured fastest result. It also
supports rows of 1..128 columns, with VL set once to the row length when
state batching is disabled (the default for these non-256-column shapes).
Other column counts use `sched=stream`, a two-pass fallback preserving
accumulator tails for partial chunks; it also keeps alpha in registers.
At the default shape, logical score reads fall from 64 KiB to 32 KiB per
invocation; score writes stay at 32 KiB. These are software access counts,
not measured bus traffic or a predicted cycle reduction. Multiply and add
round separately; reduction order can differ from RVV. Inputs, outputs
and accuracy limits match the RVV contract without requiring its schedule.

The ordinary VMU now defaults to a two-command window with per-command
translation/error ownership and disjoint-byte store combining across jobs.
Rebuild both EMU and the NEMU reference library before testing these RTL
changes; copying a new guest image alone does not enable them. Physical VRF
banks remain 1RW. For optional cumulative VMU snapshots at completed XV_FENCE
boundaries, append `-- +evu_mem_trace=1` after all EMU options. The `--` keeps
the Verilator plusarg out of the emulator's option parser. See
`docs/ONLINE_SOFTMAX_PERFORMANCE_ANALYSIS.md`, section 9, for counter meanings,
limitations and the recommended correctness/performance comparison.

The default is performance-only (`EVU_SOFTMAX_CHECK=0`). No FP64 reference,
numeric output scan, NMSE calculation or guard initialization/check is run.
EVU error status and invalid/divide-by-zero/overflow flags are still checked;
Difftest remains enabled by the EMU command. Input is generated once into
an aligned template and restored with `memcpy` before every invocation,
including after warmup. The template adds 32 KiB at the default shape and
changes the reset's cache accesses; compare warm runs under the same setup.

By default, `first=0` and `first=1` are measured independently, each with one
warmup, a full input/state reset, and one timed invocation. The timer includes
configuration, loads, arithmetic, stores, `XV_FENCE` and the final CPU memory
fence. Initialization, reference evaluation, accuracy checks and printing are
outside timing. Output is four short lines: configuration, each timed case,
and DONE. `c` is kernel cycles, `i` instructions, `warm_c` warmup cycles,
and `epc` elements/cycle. By default a separate, fully reset diagnostic
invocation also reports `pre`, `full`, `drain=full-pre` and `empty` (an idle
fence probe). The primary `c` run has no intermediate cycle probe. Diagnostic
costs include cycle-read/control overhead and must not be subtracted from `c`.
The pre-fence rdcycle also serializes core work and can permit some pending
stores to drain before the fence; `drain` is a diagnostic tail interval, not
an exact isolated memory-latency penalty.
Set `EVU_SOFTMAX_DRAIN_DIAG=0` to omit that extra invocation on later fast runs.
Require `err=0`, no invalid/divide-by-zero/overflow
flags, GOOD TRAP, and no Difftest mismatch. DONE in performance-only mode
does not report a numerical accuracy check.

Build with `EVU_SOFTMAX_CHECK=1` to restore independent accuracy checks.
That mode prints `check=1`, score/state NMSE times 1e12 (`sn12`/`stn12`)
and ends in PASS on success. Its independent FP64 checker evaluates
exponentials with polynomial-built
tables and a small residual polynomial. It validates exponentials, exact
row maxima, row sums, alpha, stride padding and guard bytes. Bounds remain
`2e-8 + 2e-5 * abs(expected)` for exp/alpha and
`1e-5 + 3e-5 * abs(expected)` for sums, with scores/state NMSE <= 1e-5.
There is no requirement that the unnormalized output sum to one.

```sh
NAME=online_softmax_evu_test make -C software/evu git_commit= ARCH=riscv64-xs TOOLCHAIN=LLVM
set -o pipefail
./build/emu --diff ../NEMU/build/riscv64-nemu-interpreter-so -C 20000000 \
  -i software/evu/build/online_softmax_evu_test-riscv64-xs.bin \
  2>&1 | tee run_online_softmax_evu.log
```

Makefile controls are `EVU_SOFTMAX_ROWS`, `EVU_SOFTMAX_COLS`,
`EVU_SOFTMAX_STRIDE`, `EVU_SOFTMAX_SCALE`, `EVU_SOFTMAX_FIRST` (-1 both,
0 carried state, 1 initial tile), `EVU_SOFTMAX_WARMUP` (0 or 1), and
`EVU_SOFTMAX_CHECK` (0 performance-only, 1 full numerical check),
`EVU_SOFTMAX_STATE_BATCH` (default 0),
`EVU_SOFTMAX_REDUCE_VL` (128/64/32,
default 128), `EVU_SOFTMAX_PIPELINE` (default 1 for cols=256, state=0, red=128;
otherwise 0), and `EVU_SOFTMAX_DRAIN_DIAG` (default 1).
Rows must be a positive multiple of four. Nondefault configurations use
distinct object directories and image names. For the existing RVV warm
comparison use rows=32, cols/stride=256, scale=1 and first=0; the no-warmup
`vfexp2_softmax_online_best` is a separate timing condition.

`tmp/doc/XSAI/softmax 优化.md` section 4.8 records 12,224 RVV cycles for
the matched warm four-row positive-scale/vector-alpha case; section 4.9
records 0.649 elements/cycle without warmup (approximately 12,620 cycles,
not an exact cycle count). These are historical numbers. The RVV timer
does not drain stores with an ending fence, whereas this asynchronous EVU
test must wait for completion. A strict comparison needs the RVV timing
boundary to include completed output stores too; do not omit EVU completion
to make the reported number smaller.

### Matched RVV Test And Optional EVU Experiments

The new `nexus-am/tests/exp2test/tests/performance/vfexp2_softmax_online_matched.c`
keeps the historical tests and their kernels unchanged. It runs the existing
four-row LMUL=4 positive-scale/vector-alpha kernel, with an ending CPU fence,
the same reset template, first=0/1, and the same optional diagnostic interval.
Both tests use `nexus-am/tests/exp2test/include/online_softmax_bench.h` for input
generation and accuracy bounds. Sync that header and both Makefiles to the
server; use the same compiler and `-fno-fast-math -ffp-contract=off` for both
images. Each matched RVV configuration has its own object directory, including
the vendor kernels, so older FMA-enabled objects are not reused.

EVU now defaults to `wave4x2`, per-row E0 states and reduction VL=128 for the
default workload. This is the preferred next measurement candidate, not a
server-measured winner. The previous schedules remain selectable.
Optional configurations, selected at build time, are:

| PIPELINE | STATE_BATCH | REDUCE_VL | Experiment |
| --- | --- | --- | --- |
| 1 | 0 | 128 | New default: paired wavefront and next-four-row prefetch |
| 0 | 0 | 128 | Previous per-row-state baseline |
| 0 | 1 | 128 | Previous scratch-packed four-row state candidate |
| 0 | 0 / 1 | 64 / 32 | Short grouped reductions, optionally batched states |

State batching uses VL=4 to load old max/sum, compute alpha/update states, and
store max/sum/alpha. Reduction results are in separate registers; with the
current software-visible ISA, they are packed using measured E0 scratch
stores and a four-element load. Gather extracts each row's max for score
arithmetic. All scratch, extraction and VL changes remain inside timing.
This is an experiment, not an assumed speedup; first=1 may save less work.

Short reductions retain all four complete rows and full-VL score arithmetic.
After pairing the two 128-element chunks, indexed gathers collect subsequent
32/64-element groups into a short accumulator, then max/sum reduces that
accumulator. Index loads, gathers, FP32 additions and VL changes are timed.
This changes sum association, not the elementwise exp operation, and currently
requires cols=256. State batching supports the resident cases only.

Build the new core test from `nexus-am/tests/exp2test` with target
`vfexp2_softmax_online_matched` and the same toolchain overrides as EVU.
Its controls have the `RVV_SOFTMAX_` prefix; defaults and meanings match the
EVU controls except for the EVU-only PIPELINE/STATE_BATCH/REDUCE_VL. The default core
image is `build/vfexp2_softmax_online_matched-riscv64-xs.bin`. The existing EVU
build command now selects wave4x2/state=0/red=128 without editing source macros,
and keeps the default image name `online_softmax_evu_test-riscv64-xs.bin`.
Append `EVU_SOFTMAX_PIPELINE=0` to recover the old per-row-state baseline, or
`EVU_SOFTMAX_REDUCE_VL=64` / `32` to try short reductions. Nondefault image names include the full
configuration. Do not run an old default image after building a named variant.

Compare the primary completed `c` fields for the same first value; use
`pre/full/drain/empty` only to diagnose endpoint costs. Both default CHECK=0
runs are performance-only. Before accepting a faster variant, build both
with their CHECK=1 controls and retain the common accuracy thresholds.
The fence/store path and CSR ordering have been inspected in source; the
server's built RTL and new disassembly still need runtime confirmation.

Source-level layout/schedule check (preprocessing and syntax checks only):

```sh
/usr/bin/python3 software/evu/scripts/check_online_softmax_schedule.py
```

This checker uses ideal exp2 and a sequential FP32 reduction. It catches
register clobbers, missed elements and out-of-range accesses, but does not
prove RTL behavior, exact unordered reduction bits, or cycle improvements.

FReduceSum uses Saturn's unordered `fredusum`. The existing focused
reference/RTL test still exercises the earlier seven-case blockwise
algorithm, not this new benchmark schedule:

```sh
bash software/evu/scripts/check_softmax_ref.sh ../NEMU
```

This uses the built NEMU library and separate RTL harnesses to check
reduction/flag semantics and the asynchronous completion/store Difftest ABI.
It does not validate the new guest schedule. Full-core EMU validation of
`online_softmax_evu_test` is required for that schedule, scalar retirement,
CSR timing and HBL2 integration.
See [the failure analysis](../../.github/evu-softmax-difftest.md).
The FP64 bounds apply only to the independent accuracy checker; Difftest
continues to require exact architectural bits and flags.

## Reference Checks

The matrix reference helper moved to `scripts/`:

```sh
bash software/evu/scripts/check_matrix_ref.sh \
  /nfs/home/guoqihang/workspace/NEMU_evu --rebuild
```

`--rebuild` selects `riscv64-xs-evu-ref_defconfig` and cleans/rebuilds that
NEMU checkout. Without it, the helper tests the existing library. It compiles
a native host regression, not a RISC-V guest. Expected negative cases print
mismatch diagnostics; success requires exit status zero and the final
`EVU matrix reference PASS` message.

The NEMU repository also provides `tools/evu-ref-test` for EVU computation
checks. Read [matrix failure analysis](docs/MATRIX_REFERENCE_DEBUG.md) for the
current `run_quanti8mma_evu.log` failure, and
[server integration steps](../../.github/evu-launch-server.md) for reference
configuration and full EMU reproduction.
