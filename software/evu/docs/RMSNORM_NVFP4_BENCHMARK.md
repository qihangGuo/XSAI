# Four-row EVU RMSNorm and NVFP4

`rmsnorm_nvfp4_evu_test.c` implements the `[4,512]` workload with FP32
arithmetic, a shared gamma[512], default epsilon=1e-5 and alpha=1/448.
The full timed path uses EVU for every numerical step. CPU arithmetic is
limited to untimed setup and correctness checks. No rsqrt7 or CPU-performance
case is included; reciprocal square root is EVU sqrt followed by division.

Format 10 adds lane-wise sum accumulation and quantization scratch renaming.
Format 11 adds bank-aware quantization register assignment and finer amax/pack
interleaving, controlled by `EVU_RMS_QUANT_BANKED=1` (the default). This software
change uses existing instructions and needs only a guest rebuild on the server.
The new schedule and the single-group slide, result forwarding and operand
lookahead RTL require server acceptance. Source-level checks are not measured
performance or an RTL PASS. See `EVU_REDUCTION_SLIDE_FASTPATHS.md` for hardware
switches and a same-software/same-RTL comparison sequence.

## Validated checkpoint (2026-11-15)

The server run with the collector invalidation fix and bank-aware format-11
software passes all 11 cases and EVU Difftest (`pending=0`). Row-major RMSNorm
takes 898 cycles, quantization 3659, and the complete operator 5101; the wave4
complete operator takes 5220. These are one-sample, no-warmup measurements.
Against the successful format-10 run, quantization improves by 8.1% and the
row-major complete operator by 6.1%; RTL and software changed together.
The [checkpoint record](checkpoints/2026-11-15-rmsnorm-5101.md) preserves all
stage results, counter comparisons, source/reference provenance and the raw
log SHA-256. Tag: `checkpoint/evu-rmsnorm-nvfp4-20261115`.

## Cases and timing

| Stage | Schedules | Timed work |
| --- | --- | --- |
| `empty` | overhead | Dispatch, timestamp and completion overhead |
| `sumsq` | row_major, wave4 | Resident inputs, square four chunks, accumulate lane-wise, reduce once per row |
| `scale_div_eps` | row_major | Four means using division by 512, plus epsilon |
| `rsqrt_evu` | row_major, wave4 | Four sqrt/divide pairs with resident inputs and 1.0 |
| `rms_evu` | row_major, wave4 | Full dependent RMSNorm with resident input/output |
| `quant_evu` | row_major | Resident y, full block amax/scaling/conversion/packing, final FP4/scale stores |
| `fused_nvfp4` | row_major, wave4 | Loads, full RMSNorm, block quantization, final FP4/scale stores |

The benchmark runs exactly 11 cases, with no warmup and one timed
execution per case by default (11 kernel invocations). Default guest printing
is 13 lines: config, 11 results, PASS. The header identifies this layout
as `samples=1 warmup=0 cases=11 format=11 quant_batch=16 quant_vl=16 quant_sew=32 quant_schedule=overlap lane_sum=1 quant_rename=1 quant_banked=1`. Each result covers
all four rows and reports `cycles`, retired CPU `inst` and `errors`, without
min/median/max statistics. Instructions include dispatch and timestamp probes.
`empty` is not subtracted; separately prepared stage costs are not additive.
Explicitly setting `EVU_RMS_SAMPLES` above 1 enables repeated measurements
and min/median/max summaries, still without warmup. CSV output keeps its
historical column names; for one measurement, min/median/max are identical.

Standalone load, multiply-by-1/512, normalization, FP32-output-store and
amax cases are removed. Normalization and amax still run inside the full
operators and are checked; the timed quantization paths store only the
final FP4 data and E4M3 scales. Full RMSNorm still uses multiplication by
1/512, while `scale_div_eps` explicitly measures division plus epsilon.
The summary tools also accept historical format-5/6/7/8 8/11-case and 17/23-case logs.

Every measured interval starts after a drain and includes its final EVU
FENCE and scalar memory fence. Check stores and CPU reference computations
are outside timing. Since quantization reuses consumed input registers, the
fused checker recomputes RMSNorm outside timing to recover its exact EVU y
values before byte-for-byte FP4/scale checking. The standalone quantization
checker uses its original reference_y input directly. No additional timed
kernel executions or intermediate-y stores are introduced.
`EVU_RMS_VERBOSE=1` also prints each sample with its exact
hardware FENCE pair (`fences=start:end`). The counter parser differences
these snapshots to exclude setup and validation traffic.

`rms_evu` has no EVU data memory traffic in its timed kernel. `quant_evu` and
`fused_nvfp4` store exactly 1,024 packed FP4 bytes and 128 E4M3 bytes, without
storing y. They load constants but no gather-index tables. Final outputs use
sixteen aligned 64-byte FP4 stores and two aligned 64-byte scale stores. The
logical store count rises from 10 to 18, but the payload remains 1,152 bytes
and 18 aligned 64-byte groups. Physical
memory traffic can exceed logical output bytes: logical stores
do not imply HBM transactions. Setup, earlier cases and CPU checking affect
cache state. Removing warmup changes cache conditions, so these results
should not be compared directly with the previous warmed measurements.

## Register layout and quantization

RMSNorm uses v0..v15 for four chunks per row, v16..v19 for gamma,
v20..v23 for square scratch, v24..v27 for row accumulators/scalars, and v28..v31
for constants. VL=128/e32/m1 processes each chunk. Each row's first square
initializes its full 128-element accumulator; three independent multiply/add
pairs accumulate the remaining chunks. Four final reductions replace sixteen
chunk reductions. Multiplication and addition remain separate FP32 operations,
not FMA. Summation association changes, so use the existing numerical tolerance
and the server difftest rather than expecting identical RMSNorm bits.

`EVU_RMS_LANE_SUM=0` restores four seeded chunk reductions per row.
`EVU_RMS_QUANT_RENAME=0` restores shared amax/packing scratch.
`EVU_RMS_QUANT_BANKED=0` restores the format-10 quantization schedule, including
its exact expanded instruction sequence for each `EVU_RMS_QUANT_RENAME` value.
All three switches default to 1 and have distinct object/binary variants.
Set all three to zero to reproduce the format-8 timed arithmetic schedule;
the header remains format 11. All variants still run exactly 11 timed cases.

Quantization starts with all normalized y in v0..v15. Each row is consumed
as two batches of 16 blocks (256 elements, two source registers per batch).
After batch 0 is packed, v0/v1 are dead and become a second inverse/mask
context. After the following batch consumes v2/v3, those registers become
dedicated steady-state maximum-reduction scratch, separate from the
packing workspaces. Other source registers remain untouched until consumed.
Input loading and the row-major/wave4 traversal order are unchanged.
`include/rmsnorm_nvfp4_batch.h` contains the actual shared schedule.

VL=16/e32/m1 remains fixed for the whole quantizer. A packed 16-element FP4
block is eight bytes, treated as two raw 32-bit words for slides, not converted
to FP32. Eight blocks fill a 64-byte output group. Four raw words similarly
carry each batch's 16 E4M3 bytes into the scale buffer. Garbage above each
narrow conversion payload is overwritten by the following insertions before
storage. Quantization configuration commands drop from 209 to one; total
expanded XV/configuration commands drop from 1,750 to 1,536. These are static
counts, not measured cycle improvements or retired CPU instruction counts.

The prologue computes batch 0 scales while collecting batch 1 maxima, then
packs batch 0 to release v0/v1. It computes batch 1 scales alongside batch 2
maxima. Steady state interleaves three phases: next-batch maximum reduction,
current-batch scale divisions/XX8 conversion, and previous-batch multiply,
MXFP4 conversion, packing and stores. The two scale contexts alternate;
division waits contain independent maximum/packing instructions. The final
batch drains the pipeline. The initial batches still share scratch, but later
amax and packing no longer reuse the same work registers. With
`quant_banked=1`, steady-state amax and packing are interleaved within each
pair: independent data extractions and scale broadcasts sit between amax
input, absolute value, reduction and insertion steps. This finer interleaving
requires `quant_rename=1`; otherwise those steps still share scratch and run
consecutively. No arithmetic is combined or reordered within an element's
numerical dependency chain.

At VLEN=4096, DLEN=512 and four banks/lane, group `g` of register `r` maps to
`(g ^ r ^ (r >> 2) ^ (r >> 4)) & 3`. The default quantizer's binary source
groups are group zero. The new assignment removes 128 static different-source
same-bank pairs from the previous `quant_rename=1` stream: 64 seed/data pairs
in max reductions and 64 factor/data pairs in MXFP4 conversions. This is a
source-level access count, not saved cycles; reduction seed timing, forwarding,
cross-instruction reads and writes affect actual contention. The static
command count remains 1,536, with 24 VL=16 divides and 18 aligned stores.
Confirm cycles and timed bank/write-block deltas with server EMU.

| Register | Content with `quant_banked=1` | First-group bank |
| --- | --- | --- |
| v0, v1 | Initially y chunks 0/1; after batch 0, second inverse/mask context | 0, 1 |
| v2, v3 | Initially y chunks; after batch 1 is consumed, steady-state amax scratch | 2, 3 |
| v4..v15 | Remaining y chunks; read-only during quantization | varies |
| v16, v27 | Two FP32 blocks, then reduction/z scratch; v16 also holds E4M3 codes | 1, 0 |
| v17 | Broadcast zero and reduction seed | 0 |
| v18, v19 | Absolute values, broadcast inverse/mask and packed FP4 scratch | 3, 2 |
| v20 | First context's candidates, decoded scales, d, then inv_d | 0 |
| v21 | First context's 16 nonzero-block masks | 1 |
| v22 | MXFP factor=127 in E0 | 2 |
| v23 | Collected 16 maxima for the next scale calculation | 3 |
| v24 | 64-byte scale buffer accumulated across four batches | 3 |
| v25 | Broadcast alpha | 2 |
| v26 | Broadcast integer one | 1 |
| v28 | Broadcast minimum nonzero E4M3 scale | 2 |
| v29 | Broadcast FP32 one | 3 |
| v30 | 64-byte FP4 output buffer for eight blocks | 0 |
| v31 | Broadcast FP32 six | 1 |

The finite-input quantization policy matches the existing reference helpers:
candidate=(m/6)/alpha, E4M3 RNE and saturation at 448, nonzero blocks get at
least code 1 (2^-9). Integer operations construct a nonzero mask without CPU
decisions on data. For nonzero blocks, clamping the FP32 candidate to 2^-9
before E4M3 conversion is code-equivalent to clamping the converted positive
E4M3 code to 1. Zero blocks retain candidate/scale zero. Reciprocal calculation
uses a safe minimum decoded scale, and a bit mask clears zero-block z to +0
before FP4 conversion. This avoids division by zero and canonicalizes zeros.

After decoding the saved scale, EVU computes d=alpha*scale and inv_d=1/d,
then z=y*inv_d. MXFP4 uses factor=127 (no additional exponent scaling), RNE,
finite saturation and low-element-first nibble packing. Per-block layout
remains `packed.data[block*8]` and `scales.data[block]`; stores aggregate these
bytes without changing the externally visible format.

Each batch executes three VL=16 vector divides: m/6, candidate/alpha, and
1/d. There are 24 such instructions for all 128 blocks, preserving the original
FP32 arithmetic order. E4M3 encode/decode also process 16 scales together.
Scale insertion/stores and subsequent block extraction can proceed while
the final reciprocal division runs. Pairwise workspaces expose overlap among
permutation, comparison/FMA and MXFP paths; actual overlap depends on the
shared queues, dependencies and VRF. Unlike format 7, the steady-state issue
stream interleaves amax/scaling/packing from different batches.

Both fused schedules use this same contiguous, row-major quantization kernel.
`wave4` changes the RMSNorm/input-load scheduling only. `quant_evu` remains one
case. Compare identical guest binaries on one-lane/16-lane emulators to isolate
divider throughput from the software scheduling improvements.

## Correctness and comparison

Floating output is checked against scalar FP32 RMSNorm with tolerance
`2e-5*(1+abs(reference))`. After fused timing, RMSNorm is replayed and y is
stored for checking; quantization is compared against the CPU reference
applied to this deterministic EVU y, so valid reduction-order differences at
quantization thresholds are tolerated. Standalone quantization is checked
directly against its reference_y input.
Packed data and scale codes must match exactly, and buffer guards are checked.
NV/DZ and EVU errors fail. Quantization may legitimately report OF/NX on
finite-saturating conversions; the bit-exact instruction tests check flags.
The independent quantization helper oracle remains a server-side test.

`EVU_RMS_BF16_INPUT=1` only rounds inputs/gamma to BF16 precision before
storing and processing them as FP32. It does not measure native BF16 bandwidth.
NaN/Inf inputs are covered by instruction tests, not the full RMSNorm workload.

`EVU_RMS_ONLY=1` selects the eight non-quantization cases (`cases=8 format=11`)
and needs capability
bit28 only. Build this image once and run the identical image on old/new
emulators (each with its matching NEMU) to assess conversion-FU integration
overhead. The old emulator must already implement the RVV-math extension.
Do not compare different source/timing versions as a hardware-only change.

## Server acceptance

For the format-11 software change, keep the current EMU and matching NEMU
unchanged. Run from the repository root with `AM_HOME` set:

```sh
bash software/evu/scripts/compare_rmsnorm_software.sh ../NEMU_evu
```

This builds only two guest variants and runs both with timed FENCE snapshots:
`bank0` has the previous format-10 quantization instructions, and `bank1` has
the new register assignment/interleaving. Both use `lane_sum=1 quant_rename=1`,
the same timing boundary and `EVU_RMS_VERBOSE=1`. The default is one sample
per case; prefix the command with `EVU_RMS_SAMPLES=3` for repeated measurements.
Outputs go to `out/rmsnorm-software`: `bank0.log`, `bank1.log`,
`comparison.csv`, per-variant cycle/counter CSVs and artifact SHA256 hashes.
`EVU_EMU` and `EVU_RMS_SOFTWARE_OUTPUT` override the emulator and output path.
An argument can name an absolute NEMU checkout path. No EMU/NEMU rebuild is
performed. Check both PASS/GOOD TRAP and pending=0 before interpreting cycles;
the script rejects failed/incomplete runs. Inspect `quant_evu` and both
`fused_nvfp4` cases plus their timed bank/write-block/operand deltas. The
existing `rmsnorm.log` result (3683 quantization, 5131/5264 fused cycles) is
historical context; use the paired server runs to attribute software gains.

`DefaultExternalVectorMatrixConfig` enables 16 parallel FP32 divide/sqrt lanes
at DLEN=512. `DefaultExternalVectorScalarDivMatrixConfig` retains the scalar
divider for controlled comparison. FP16 and estimate/classification operations
remain element-wise. Each FP32 lane is still iterative; group throughput
improves, not the latency of one scalar divide. Short unordered reductions
merge only accumulator slots that received input groups.

No xv encoding or NEMU arithmetic semantics change. Use the existing updated
NEMU checkout, including its previously added slide and low-precision support.
This software-only schedule change does not require rebuilding EMU or NEMU
when the previous 16-lane/low-precision hardware is already available. Rebuild
only the guest on the server:

```sh
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=rmsnorm_nvfp4_evu_test
./build/emu --diff ../NEMU_evu/build/riscv64-nemu-interpreter-so \
  -C 100000000 -i software/evu/build/rmsnorm_nvfp4_evu_test-riscv64-xs.bin
```

Focused server RTL tests are:

```sh
mill -i evuCheck.test.testOnly evu.EvuParallelDivSpec
mill -i evuCheck.test.testOnly evu.EvuInterleavedReductionSpec -- -z "short FP32"
```

The lightweight `test_rmsnorm_batch_schedule` uses only C preprocessing and a
Python instruction model, not a compiled guest or RTL simulation. It executes
the actual batch macros against an independent scalar quantization oracle,
checks live input preservation, safe reuse of v0/v1 and cross-batch issue
ordering. It requires one VL=16/e32 configuration, exactly 24 VL=16 divides
and 18 aligned stores totaling 1152 bytes. It complements, but does not
replace, server NEMU/RTL validation.

See `EVU_LOW_PRECISION.md` for source sync, RTL/reference checks and build.
After building the new emulator, the following server script runs instruction
smoke, add/exp2/CSR regression, checked Softmax, Softmax performance, RMSNorm
and complete NVFP4, then writes cycle and counter CSVs:

```sh
bash software/evu/scripts/check_low_precision_emu.sh ../NEMU_evu
```

Set `EVU_BEFORE_EMU` and `EVU_BEFORE_REF` to saved matching pre-change
artifacts to additionally compare the same Softmax and RMSNorm-only images.
`EVU_EMU` and `EVU_LOW_PRECISION_OUTPUT` optionally override emulator/output.
The script builds guest images on the server, but does not rebuild EMU/NEMU.

For existing logs:

```sh
python3 software/evu/scripts/summarize_rmsnorm_nvfp4.py nvfp4.log > cycles.csv
python3 software/evu/scripts/summarize_rmsnorm_counters.py nvfp4.log > counters.csv
python3 software/evu/scripts/compare_rmsnorm_runs.py before.log after.log > comparison.csv
```

Counters require `EVU_RMS_VERBOSE=1` and `+evu_mem_trace=1`. They report VRF
bank conflicts, writeback waits, writes blocking reads, XX8/MXFP issues and
logical output traffic. Existing diagnostic snapshots also supply dependency,
operand/feedback/permutation waits, read grant/response waits, FMA/CMP queue
waits and conversion operand/queue/idle counters; absent historical diagnostic
fields are left blank rather than treated as zero. Counters overlap and must
not be summed into lost cycles. A complete NVFP4 interval with logical store bytes
other than 1,152 is rejected. Physical frequency/area needs separate synthesis.
