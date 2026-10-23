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
| `online_softmax_evu_test` | FP32 blockwise online max/sum, Exp2, reductions, gather broadcast and normalization |
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

`online_softmax_evu_test.c` processes 512 FP32 values as four 128-element blocks.
It uses ordinary EVU/Saturn operations, with no CUTE launch, quantization,
descriptor or dedicated Softmax instruction. All input blocks have full VL
and are unmasked; partial VL, predication and exceptional inputs are outside
this test's scope.

For each block, EVU computes:

```text
m_new = max(m_old, max(block))
l_new = l_old * exp(m_old - m_new) + sum(exp(block - m_new))
exp(x) = exp2(x * log2(e))
```

The first block initializes `m` and `l` directly. Maximum and sum reductions
produce element zero; GATHER broadcasts that element. Running state stays in
EVU registers across blocks. A second pass computes every block's
`exp(input - final_m) / final_l`, so early outputs use the final row state.

Seven cases cover uniform inputs, increasing/decreasing/alternating block
maxima, shifts by +64 and -64, and a late single peak. The checker validates
each block's exponent values, every broadcast max/sum lane, final
probabilities, shift invariance, positivity, probability sum, input/constants
preservation and guard bytes. It uses an independent double-precision scalar
Taylor reference, without libm or EVU outputs as the expected result. Failure
messages name the case, stage, block and element, with FP32 values in hex.

```sh
NAME=online_softmax_evu_test make -C software/evu git_commit= ARCH=riscv64-xs TOOLCHAIN=LLVM
set -o pipefail
./build/emu --diff ../NEMU/build/riscv64-nemu-interpreter-so -C 20000000 \
  -i software/evu/build/online_softmax_evu_test-riscv64-xs.bin \
  2>&1 | tee run_online_softmax_evu.log
```

FReduceSum uses Saturn's unordered `fredusum`. The NEMU EVU reference now
models its four round-robin element-group accumulators, ordered slot merges
and upper-half lane folds, with element-width rounding after every add.
Rebuild the NEMU reference to include this model; the old serial sum produces
`0x4200d92b` instead of RTL's `0x4200d92a` for case 1, block 1.

The segmented FMA path also now reports all five exception flags on the
correct result bytes, and reduction folds enable only participating lanes.
Rebuild EMU for these fixes. Numerical tolerances below apply only to the
independent accuracy checker; Difftest continues to require exact bits and flags.

Run the focused comparison without a full-core EMU build:

```sh
bash software/evu/scripts/check_softmax_ref.sh ../NEMU
```

This uses the built NEMU library, independently checks accuracy against
long-double `expl`, compares 690 RTL instructions (including 112 vector
stores) with NEMU, and replays actual RTL outputs through NEMU's asynchronous
completion/store Difftest ABI. It checks all 128 destination words per
instruction, including preserved reduction tails. Full-core EMU validation
is still required for scalar retirement, CSR timing and HBL2 integration.
See [the failure analysis](../../.github/evu-softmax-difftest.md).

The exponent checker allows `2e-8 + 2e-5 * abs(expected)` error; the online
denominator allows `1e-5 + 3e-5 * abs(expected)`. Final probabilities allow
`2e-8 + 5e-5 * abs(expected)` and must sum to one within `2e-4`. Maxima must
match exactly. These bounds allow FP32 conversion/Exp2 approximation and
reduction rounding; they do not require bit-identical scalar execution.

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
