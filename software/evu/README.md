# Pure Long-Vector EVU Tests

The current EVU provides ordinary long-vector execution with VLEN=4096,
DLEN=512, MLEN=512 and a 16 KiB VRF built from 32 single-port 64x64 banks.
The default configuration remains `DefaultExternalVectorMatrixConfig`; CUTE
is an independent accelerator in that SoC configuration. There is no direct
EVU/CUTE data connection, Launch, or dedicated quantization/dequantization path.

All commands below run from the XSAI repository root, on the build server.
Use the server's existing `AM_HOME`, RISC-V LLVM toolchain and NEMU checkout.

## Supported Tests

| NAME | Coverage |
| --- | --- |
| `vector_add_evu_test` | Integer e8/e16/e32 arithmetic, aliases, memory and guards |
| `exp2_evu_test` | FP16/FP32 EXP2 and ordinary vector execution |
| `csr_context_evu_test` | Independent EVU CSR ABI 2, returned VL and context restore |
| `virtual_memory_evu_test` | Sv39 translation, permissions and precise memory faults |
| `vector_memory_path_evu_test` | Bulk/state load-store patterns and cache-state diagnostics |
| `online_softmax_evu_test` | Ordinary FP32 softmax, four-row scheduling and interval diagnostics |
| `rvv_math_evu_test` | 15 RVV-mapped operations, m1/m2/mf2, E0, tails, flags and saturation |
| `low_precision_evu_test` | Six low-precision operations, 88 directed cases, packing, tails, flags and guards |
| `slide_evu_test` | 216 slide-up/down cases covering widths, LMUL, aliases, offsets and tails |
| `rmsnorm_nvfp4_evu_test` | EVU RMSNorm and full NVFP4 with final-output-only stores; 11 cases |
| `rmsnorm_nvfp4_fused_evu_test` | Only fused NVFP4 row-major; timed FENCE snapshots and CPU-only checking |

`Makefile` defaults to `vector_add_evu_test`. The old fused FlashAttention and
`quanti8mma_evu_example` programs are retired; their binaries cannot run on the
pure-vector ISA. Their sources and obsolete documentation remain available in
Git history, not in the current test suite.

The production command window is a unified 32-row VAT/job table without a
completion FIFO. Two ordinary FMA contexts share one arithmetic datapath.
The integer path has one full context and one restricted short-instruction
context, sharing one execution unit. Reduction owners support same-cycle
handoff, and completion arbitration prioritizes older commands.
The `docs/` directory holds local research notes and is not part of the
public checkout; supported build, interface and validation information is below.

## Server Build and Run

For a committed checkout, initialize the pinned dependencies:

```sh
git submodule sync --recursive
git submodule update --init --recursive
```

The hardware dependency baseline is official XSAI commit `63c767f1`. The
parent pins the EVU difftest fork at `d5fd0480bdd8ea3640eb808fe7e6f6624e432d1a`,
which already includes the ordinary EVU extension on top of `0fdb354a`.
No additional patch or patch-application script is required.
When synchronizing an uncommitted workspace, include the changed difftest
sources and new/untracked files as well as the parent sources. An rsync that
excludes `.git` does not update submodule HEADs or their tracked-file lists.
The server must also use the revisions pinned by the current parent commit.
Do not run a blind submodule update against an older parent commit: that restores its old
dependencies. Preserve server-side changes before switching revisions.
The standalone native MMU reference harness has been removed; the
`virtual_memory_evu_test` guest remains available for server RTL validation.

Rebuild the reference, simulator and guest together after this ISA change:

```sh
make -C ../NEMU git_commit= riscv64-xs-evu-ref_defconfig
make -C ../NEMU git_commit= clean
make -C ../NEMU git_commit= -j8

make emu CONFIG=DefaultExternalVectorMatrixConfig \
  ACCELERATOR_CDC_ENABLE=0 EMU_THREADS=8 EMU_TRACE=fst -j16 2>&1 | tee xsai_build.log

make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=vector_add_evu_test
./build/emu --diff ../NEMU/build/riscv64-nemu-interpreter-so \
  -C 100000000 -i software/evu/build/vector_add_evu_test-riscv64-xs.bin
```

For each supported test, require GOOD TRAP, no assertion/difftest failure and
zero outstanding EVU commands. Ordinary stores complete only after real
physical write acknowledgements. FENCE drains older EVU work. WAIT has been
removed; selector 0x19 is illegal and old binaries must be rebuilt using
`XV_FENCE()`. Each ordinary command publishes its own flags and errors.

`XV_SLIDEUP(vd, offset, vs, sew)` and `XV_SLIDEDOWN(vd, offset, vs, sew)`
take an unsigned 64-bit scalar offset. They map to Saturn's `.vx` forms;
the old tentative VRF-element-zero offset convention no longer applies.

## Software Interface and Numerical Rules

The public instruction and CSR helpers are [evu_intrinsics.h](include/evu_intrinsics.h)
and [evu_csr.h](include/evu_csr.h). EVU uses private custom encodings, not the
standard RVV binary interface. ABI 2 uses a 13-word CSR snapshot and returned-VL
configuration: `xv_setvl` and `xv_setvli` return the actual vector length.
Unsupported vector types set VILL and VL=0; removed descriptor CSRs are illegal.
Software context save/restore uses the versioned helpers rather than assuming
that the execution contexts inside the backend are architectural contexts.

Low-precision instructions use e32/m1 with VL counting logical FP32 elements.
Only active output bytes are written; other destination bytes are preserved.
Conversion rounding is RNE. E4M3 saturates at magnitude 448, and MXFP4 uses E2M1
with finite saturation at magnitude 6. FP4 packs the earlier element into the
low nibble. MXFP scale operands use UE8M0, not E4M3; code 127 means a factor
of one. Source/destination overlap and unsupported configurations are rejected.
The low-precision and RVV-math tests check packing, tails and exception flags.

## Online Softmax

Build the numerical-check variant first; the default runs both `first` modes:

```sh
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM \
  NAME=online_softmax_evu_test EVU_SOFTMAX_CHECK=1
```

Nondefault build options produce a variant-specific image path printed by the
Makefile. Use that path for the correctness run. For the default performance
image and formal FENCE interval counters:

```sh
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=online_softmax_evu_test
./build/emu --diff ../NEMU/build/riscv64-nemu-interpreter-so \
  -C 100000000 -i software/evu/build/online_softmax_evu_test-riscv64-xs.bin \
  -- +evu_mem_trace=1 2>&1 | tee evu_softmax.log
/usr/bin/python3 software/evu/scripts/summarize_softmax_fences.py evu_softmax.log
```

The kernel contains no CUTE operation, dedicated quantization or final
probability normalization. Compare identical software options, warmup and
completion boundaries. Historical cycle counts are not new migration results.
The schedule is implemented in [online_softmax_bench.h](include/online_softmax_bench.h).

## Focused Validation

Lightweight parser checks do not build or run hardware:

```sh
/usr/bin/python3 -B -m unittest discover -s software/evu/scripts -p 'test_summarize_*.py'
```

Run every supported guest above with the matching reference library on the
server, including configuration, memory, slide and low-precision regressions.
Retired funct7 values `0x18`, `0x50` and `0x5e` must trap at every encoded
element width, including VL=0, with no memory or register effects. EVCAP bit 25
reads zero. Other instruction encodings and the 13-word CSR snapshot stay at
ABI 2. FAST/FORK reference modes and incomplete accelerator replay are rejected
when the required EVU/reference-state support is unavailable.

## RMSNorm / NVFP4 Performance Baseline

`rmsnorm_nvfp4_evu_test` measures `[4,512]` FP32 inputs with stage-level cycles,
row-major versus four-row-interleaved EVU issue, and actual division by 512
plus epsilon. Reciprocal square root uses only EVU sqrt followed by division.
Complete RMSNorm is measured with VRF-resident input/output; full
RMSNorm/NVFP4 includes input loads, block amax, EVU quantization and final
output stores, with no intermediate-y stores.
The default RTL has 16 FP32 divide/sqrt lanes. Quantization batches 16 block
scales, keeps VL=16/e32 throughout, and overlaps next-batch amax, current-batch
scale computation and previous-batch FP4 packing. Consumed input registers
provide a second scale/mask context. Outputs use aligned 64-byte FP4/scale
stores. Format 10 adds one final sum reduction per row and separates steady-state
amax scratch from packing scratch. Format 11 adds bank-aware register placement
and finer amax/packing interleaving. `EVU_RMS_QUANT_BANKED=0` restores the
format-10 quantization schedule; setting `EVU_RMS_LANE_SUM=0`,
`EVU_RMS_QUANT_RENAME=0` and `EVU_RMS_QUANT_BANKED=0` restores the format-8
arithmetic schedule for same-RTL comparisons. The one-lane control
configuration is `DefaultExternalVectorScalarDivMatrixConfig`.
CPU arithmetic is used only outside timing for setup/checking. There are
11 cases, each measured once without warmup by default, and no
CPU-performance or rsqrt7/Newton cases. Native BF16 is not
measured. `EVU_RMS_ONLY=1` selects the eight-case RMSNorm-only regression image.
Run `bash software/evu/scripts/compare_rmsnorm_software.sh ../NEMU_evu` on the
server to build and compare both bank-aware software variants on the same EMU,
including checked per-interval cycle and counter CSVs in `out/rmsnorm-software`.

The numerical definition uses shared gamma[512], epsilon=1e-5 and alpha=1/448.
Each 16-element quantization block computes amax, rounds `(amax/6)/alpha` to an
E4M3 scale, decodes that saved scale, then forms `y * (1/(alpha*scale))` for FP4.
Nonzero blocks use at least scale code 1; zero blocks produce zero scale/data
without division by zero. Final outputs are 1,024 packed FP4 bytes and 128 scale
bytes, issued as eighteen aligned 64-byte stores. The reference implementation
is [rmsnorm_nvfp4_ref.h](include/rmsnorm_nvfp4_ref.h).
`EVU_RMS_BF16_INPUT=1` rounds inputs to BF16 precision but still computes in FP32.

For a single fused row-major sample with no warmup:

```sh
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=rmsnorm_nvfp4_fused_evu_test
./build/emu --diff ../NEMU/build/riscv64-nemu-interpreter-so \
  -C 100000000 -i software/evu/build/rmsnorm_nvfp4_fused_evu_test-riscv64-xs.bin \
  -- +evu_mem_trace=1 2>&1 | tee rmsnorm_nvfp4_fused_evu_test.log
/usr/bin/python3 software/evu/scripts/summarize_rmsnorm_counters.py rmsnorm_nvfp4_fused_evu_test.log
```

The fused-only test checks FP4 and scale bytes exactly using untimed CPU
arithmetic; its reduction oracle assumes DLEN=512. Timing starts after preparation
and draining, includes input loads and final output stores, and ends after EVU
and scalar-memory fences. Initialization, configuration and checking are outside
timing. There is no empty-case subtraction. Compare the same image, options,
sample index and timing boundaries between hardware revisions. FENCE-delta
counters isolate an interval only when the corresponding hardware snapshots
are present; end-of-guest wait counters are not automatically interval counts.
