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
| `rmsnorm_nvfp4_evu_test` | EVU RMSNorm and full NVFP4 with final-output-only stores; 11 cases |

`Makefile` defaults to `vector_add_evu_test`. The old fused FlashAttention and
`quanti8mma_evu_example` programs are retired; their binaries cannot run on the
pure-vector ISA. Historical documents and measurements are under
[`docs/archive/matrix-vector/`](docs/archive/matrix-vector/README.md).

## Server Build and Run

For a committed checkout, initialize the pinned dependencies:

```sh
git submodule sync --recursive
git submodule update --init --recursive
bash software/evu/scripts/apply_pure_vector_difftest.sh
```

The dependency baseline is official XSAI commit `63c767f1`, with the ordinary
EVU difftest extension applied to difftest `0fdb354a`. See the exact revisions
and integration requirements in [`EVU_PURE_VECTOR_PLAN.md`](docs/EVU_PURE_VECTOR_PLAN.md).
When synchronizing an uncommitted workspace, include the changed difftest
sources and new/untracked files as well as the parent sources. An rsync that
excludes `.git` does not update submodule HEADs or their tracked-file lists.
The server must also use the pinned revisions in the plan. Do not run a
blind submodule update against an older parent commit: that restores its old
dependencies. Preserve server-side changes before switching revisions.
The application script is idempotent and keeps the official difftest gitlink;
`--check` verifies an already prepared checkout without modifying it.

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
See [slide and synchronization validation](docs/EVU_SLIDE_AND_FENCE.md).

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

## Focused Validation

Lightweight parser checks do not build or run hardware:

```sh
/usr/bin/python3 -m unittest discover -s software/evu/scripts -p 'test_*.py'
```

Server-side reference/RTL tests are described in
[`EVU_PURE_VECTOR_PLAN.md`](docs/EVU_PURE_VECTOR_PLAN.md).
Retired funct7 values `0x18`, `0x50` and `0x5e` must trap at every encoded
element width, including VL=0, with no memory or register effects. EVCAP bit 25
reads zero. Other instruction encodings and the 13-word CSR snapshot stay at
ABI 2. FAST/FORK reference modes and incomplete accelerator replay are rejected
when the required EVU/reference-state support is unavailable.

Migration status, checks actually run and outstanding server validation are
recorded in [`EVU_PURE_VECTOR_PROGRESS.md`](docs/EVU_PURE_VECTOR_PROGRESS.md).

## RMSNorm / NVFP4 Performance Baseline

`rmsnorm_nvfp4_evu_test` measures `[4,512]` FP32 inputs with stage-level cycles,
row-major versus four-row-interleaved EVU issue, and actual division by 512
plus epsilon. Reciprocal square root uses only EVU sqrt followed by division.
Complete RMSNorm is measured with VRF-resident input/output; full
RMSNorm/NVFP4 includes input loads, block amax, EVU quantization and final
output stores, with no intermediate-y stores.
The default RTL has 16 FP32 divide/sqrt lanes. Quantization batches 16 block
scales, uses continuous slides and paired block workspaces, and aggregates
outputs into aligned 128-byte FP4 / 64-byte scale stores. The one-lane control
configuration is `DefaultExternalVectorScalarDivMatrixConfig`.
CPU arithmetic is used only outside timing for setup/checking. There are
11 cases, each measured once without warmup by default, and no
CPU-performance or rsqrt7/Newton cases. Native BF16 is not
measured. `EVU_RMS_ONLY=1` selects the eight-case RMSNorm-only regression image.
Six new instructions, independent XX8/MXFP issue paths and server acceptance
are documented in [`EVU_LOW_PRECISION.md`](docs/EVU_LOW_PRECISION.md).
See [`RMSNORM_NVFP4_BENCHMARK.md`](docs/RMSNORM_NVFP4_BENCHMARK.md) for numerical
conventions, build/run commands, BF16-valued FP32 input mode and CSV summaries.
The instruction extension and server validation sequence are documented in
[`EVU_RVV_MATH.md`](docs/EVU_RVV_MATH.md).
