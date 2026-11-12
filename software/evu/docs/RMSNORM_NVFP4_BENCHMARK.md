# Four-row EVU RMSNorm and NVFP4

`rmsnorm_nvfp4_evu_test.c` implements the `[4,512]` workload with FP32
arithmetic, a shared gamma[512], default epsilon=1e-5 and alpha=1/448.
The full timed path uses EVU for every numerical step. CPU arithmetic is
limited to untimed setup and correctness checks. No rsqrt7 or CPU-performance
case is included; reciprocal square root is EVU sqrt followed by division.

The new low-precision RTL and software still require server compilation and
execution. Source-level checks are not measured performance or an RTL PASS.

## Cases and timing

| Stage | Schedules | Timed work |
| --- | --- | --- |
| `empty` | overhead | Dispatch, timestamp and completion overhead |
| `sumsq` | row_major, wave4 | Resident inputs, four 128-element squares/reductions per row |
| `scale_div_eps` | row_major | Four means using division by 512, plus epsilon |
| `rsqrt_evu` | row_major, wave4 | Four sqrt/divide pairs with resident inputs and 1.0 |
| `rms_evu` | row_major, wave4 | Full dependent RMSNorm with resident input/output |
| `quant_evu` | row_major | Resident y, full block amax/scaling/conversion/packing, final FP4/scale stores |
| `fused_nvfp4` | row_major, wave4 | Loads, full RMSNorm, block quantization, final FP4/scale stores |

The benchmark runs exactly 11 cases, with no warmup and one timed
execution per case by default (11 kernel invocations). Default guest printing
is 13 lines: config, 11 results, PASS. The header identifies this layout
as `samples=1 warmup=0 cases=11 format=7 quant_batch=16`. Each result covers
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
The summary tools also accept historical format-5/6 8/11-case and 17/23-case logs.

Every measured interval starts after a drain and includes its final EVU
FENCE and scalar memory fence. Check stores and CPU reference computations
are outside timing. `EVU_RMS_VERBOSE=1` also prints each sample with its exact
hardware FENCE pair (`fences=start:end`). The counter parser differences
these snapshots to exclude setup and validation traffic.

`rms_evu` has no EVU data memory traffic in its timed kernel. `quant_evu` and
`fused_nvfp4` store exactly 1,024 packed FP4 bytes and 128 E4M3 bytes, without
storing y. They load constants but no gather-index tables. Final outputs use
eight aligned 128-byte stores and two aligned 64-byte scale stores. Physical
memory traffic can exceed logical output bytes: logical stores
do not imply HBM transactions. Setup, earlier cases and CPU checking affect
cache state. Removing warmup changes cache conditions, so these results
should not be compared directly with the previous warmed measurements.

## Register layout and quantization

RMSNorm uses v0..v15 for four chunks per row, v16..v19 for gamma,
v20..v23 for square scratch, v24..v27 for row scalars, and v28..v31 for
constants. VL=128/e32/m1 processes each chunk.

Quantization keeps all normalized y in v0..v15. Each batch covers 16 blocks
(256 elements, two source registers). Two block workspaces interleave
continuous slides, absolute values, reductions and packed conversions.
`include/rmsnorm_nvfp4_batch.h` contains the actual shared schedule.

| Register | Content |
| --- | --- |
| v16, v17 | Two contiguous 16-element FP32 blocks |
| v18, v19 | Two absolute-value/z workspaces; v18 temporarily broadcasts six |
| v20 | First amax result, then 16 candidate scales, then 128 packed output bytes |
| v21 | Second amax result, then 16 nonzero-block masks |
| v22 | Collected 16 amax values, then first block's inverse/mask/packed scratch |
| v23 | 16 E4M3 scale bytes, then second block's inverse/mask/packed scratch |
| v24 | 64-byte scale buffer accumulated across four batches |
| v25..v30 | Alpha, one, minimum scale, zero, integer one, factor=127 |
| v31 | 16 decoded scales, actual scales d, then reciprocals inv_d |

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
shared queues, dependencies and VRF. Batches retain amax/scaling/packing phases;
they are not a fully overlapped pipeline across independent batches.

Both fused schedules use this same contiguous, row-major quantization kernel.
`wave4` changes the RMSNorm/input-load scheduling only. `quant_evu` remains one
case. Compare identical guest binaries on one-lane/16-lane emulators to isolate
divider throughput from the software scheduling improvements.

## Correctness and comparison

Floating output is checked against scalar FP32 RMSNorm with tolerance
`2e-5*(1+abs(reference))`. After timing, y is stored for checking; quantization
is compared against the CPU reference applied to the actual checked y, so
valid reduction-order differences at quantization thresholds are tolerated.
Packed data and scale codes must match exactly, and buffer guards are checked.
NV/DZ and EVU errors fail. Quantization may legitimately report OF/NX on
finite-saturating conversions; the bit-exact instruction tests check flags.
The independent quantization helper oracle remains a server-side test.

`EVU_RMS_BF16_INPUT=1` only rounds inputs/gamma to BF16 precision before
storing and processing them as FP32. It does not measure native BF16 bandwidth.
NaN/Inf inputs are covered by instruction tests, not the full RMSNorm workload.

`EVU_RMS_ONLY=1` selects the eight non-quantization cases (`cases=8 format=7`)
and needs capability
bit28 only. Build this image once and run the identical image on old/new
emulators (each with its matching NEMU) to assess conversion-FU integration
overhead. The old emulator must already implement the RVV-math extension.
Do not compare different source/timing versions as a hardware-only change.

## Server acceptance

`DefaultExternalVectorMatrixConfig` enables 16 parallel FP32 divide/sqrt lanes
at DLEN=512. `DefaultExternalVectorScalarDivMatrixConfig` retains the scalar
divider for controlled comparison. FP16 and estimate/classification operations
remain element-wise. Each FP32 lane is still iterative; group throughput
improves, not the latency of one scalar divide. Short unordered reductions
merge only accumulator slots that received input groups.

No xv encoding or NEMU arithmetic semantics change. Use the existing updated
NEMU checkout, including its previously added slide and low-precision support.
Rebuild the emulator and guest on the server:

```sh
make emu CONFIG=DefaultExternalVectorMatrixConfig \
  ACCELERATOR_CDC_ENABLE=0 EMU_THREADS=8 EMU_TRACE=fst -j16
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
checks resident-y preservation and requires exactly 24 VL=16 divides and 10
aligned stores totaling 1152 bytes. It complements, but does not replace, server
NEMU/RTL validation.

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
logical output traffic. A complete NVFP4 interval with logical store bytes
other than 1,152 is rejected. Physical frequency/area needs separate synthesis.
