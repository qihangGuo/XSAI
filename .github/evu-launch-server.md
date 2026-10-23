# EVU FP32 Matrix Launch and Server Validation

The target is `DefaultExternalVectorMatrixConfig`: Saturn-backed EVU,
VLEN=4096 and DLEN=256. Rebuild NEMU, EMU and the guest from matching current
sources. The Difftest matrix-result events and physical-store checks require
both sides to agree; an older EMU or reference library is insufficient.

## Datapath and Reference Contract

`EvuSpecialSequencer` sends CUTE matrix beats through `EvuMatrixDequant`,
which owns an `EvuQuant` instance. Scale operands are collected independently
from the shared VRF using the global matrix coordinates. Backpressure must
preserve the data, coordinates and arithmetic flags until acceptance.

For FP32 matrix launch, the arithmetic is
`FP32(FP32(float(C[n,m]) * scaleA[m]) * scaleB[n])`, with RNE at each
conversion/product and accumulated IEEE flags. Combining these rounding
points would change the contract.

The NEMU reference subset supports M/Bare mode, signed INT8 tile loads and a
fresh accumulator `mzero` after launch, followed by one full 128x128 INT32 MMA
with K<=64. A terminal `xv.fence` is required before another launch or reuse of
output/scale memory. Unsupported modes, producer sequences and shapes are
rejected; this is not a complete AME/EVU reference implementation.

The reference computes expected results from independent AME shadow operands
and accumulators at instruction execution. It checks matrix-result data,
coordinates, masks, flags and final-beat indication, then checks acknowledged
physical writes and preserved guard bytes.

Launch arms the consumer and completes before subsequent AME commands. WAIT
waits for its target command and returns zero matrix status. FENCE waits for
the matrix stream and final output acknowledgement, then publishes flags and
errors. Reused command IDs must not make WAIT depend on its own allocation.

The dedicated Softmax path is removed. See
[retired instructions](evu-softmax-removal.md) and
[virtual-memory restrictions](evu-virtual-memory.md).

## Verification Status

Focused matrix datapath/control tests, Scala compilation and Saturn+Difftest
CHIRRTL elaboration have passed in earlier checks. The NEMU reference library
also built in an earlier check. These results predate this documentation cleanup.

The native matrix harness now uses a fresh PC for every injected instruction.
Rewriting one PC previously reused NEMU's cached decode and caused the first
asynchronous LOAD completion check to fail. The corrected harness passed
three launches, 6,144 result beats and 6,144 RMW stores locally, including
invalid state, size, coordinate, alignment and mask checks.

The server's full-core EMU run reports `not-ready active=1 ready=0` on its
first EVU matrix event at cycle 30432. Its first DUT beat is numerically
correct, but the reference has no computed result despite its PC being past
MMA. Validate the exact server library with the native harness after a clean
rebuild; the server library and a passing full-core run remain unverified.

The Store completion fix keeps Saturn's internal VAT release and instead
exports VMU completion after the final ordered write Ack. The focused
regression reproduces early completion in the old implementation and passes
with the fix, including delayed/error Acks at 8/16/32-bit element widths.

## Server Environment

These examples use the existing open06 repository locations. Adjust
`NEMU_REPO` if the reference checkout has a different name. Set `AM_HOME`
to the server's existing Abstract Machine checkout and use its LLVM toolchain.
Commands are intended to run in one shell.

```sh
export XSAI_REPO=/nfs/home/guoqihang/workspace/XSAI_external_vector
export NEMU_REPO=/nfs/home/guoqihang/workspace/NEMU_evu
export NEMU_HOME="$NEMU_REPO"
```

Use current source synchronization, including file deletions. The old `/tmp`
transfer archives and `.github` checksum/file manifests describe earlier
snapshots and are not the current source package.

## Build the Reference

```sh
cd "$NEMU_REPO"
make git_commit= riscv64-xs-evu-ref_defconfig
make git_commit= clean
make git_commit= -j8
file build/riscv64-nemu-interpreter-so
nm -D --defined-only build/riscv64-nemu-interpreter-so | rg 'difftest_evu_'
```

The EVU API has eight exports, including `difftest_evu_matrix`. The output
has no `.so` suffix but is an ELF shared library. `git_commit=` suppresses
NEMU's automatic build commit.

## Native Reference Regression

This exercises the reference API without RTL; passing does not validate CUTE
or the full-core EVU integration.

```sh
cd "$XSAI_REPO"
mkdir -p out
cc -std=gnu11 -O2 -D__ISA__=riscv64 \
  -I "$NEMU_REPO/include" -I "$NEMU_REPO/src/isa/riscv64/include" \
  -I "$NEMU_REPO/src/isa/riscv64/instr" \
  software/evu/host/evu_matrix_ref_test.c -o out/evu-matrix-ref-test -ldl
./out/evu-matrix-ref-test "$NEMU_REPO/build/riscv64-nemu-interpreter-so"
```

Acceptance requires exit 0 and the final summary reporting three launches,
6,144 result beats and 6,144 RMW stores. Deliberately injected wrong data,
flags, guards, duplicate beats and early drains must be rejected. The third
launch checks inexact arithmetic, WAIT's zero flags and FENCE's NX. A CHECK
failure or missing final summary is a failure, even if mismatch diagnostics
also appear during intentional negative checks.

The combined rebuild and native validation command is:

```sh
cd "$XSAI_REPO"
set -o pipefail
bash software/evu/scripts/check_matrix_ref.sh "$NEMU_REPO" --rebuild \
  2>&1 | tee check_matrix_ref.log
```

Synchronize all NEMU sources first, including `instr/ame/mcompute.h`,
`instr/ame/mldst_impl.c` and `instr/evu/`. This command replaces NEMU's
configuration with the EVU reference preset and cleans its build directory.
The final `EVU matrix reference PASS` is required before the full-core run.

## Diagnose a Matrix Event Failure

The reference now reports every rejected matrix event as
`EVU matrix mismatch reason=...`, including state, dimensions, byte count,
mask, flags, last and processed-element count. For a valid data buffer it
also prints DUT FP32 bit patterns, and REF patterns when the reference
result and coordinates are available. Reasons distinguish inactive or
not-ready reference state, invalid size/coordinates/alignment/mask,
duplicates, data, flags and last mismatches.

The diagnostic also prints producer hooks observed since launch. The example
expects `zero_mask=0x10 load_mask=0x3 mma_count=1` after MMA. A `not-ready`
failure with `mma_count=0` means the independent MMA hook did not run after
this launch. Preserve the complete block and the native harness result if
the clean rebuild still fails. Do not skip the readiness check or substitute
DUT results for the independent reference result.

## Build EMU and Guest, Then Run Difftest

```sh
cd "$XSAI_REPO"
make -W src/main/scala/xiangshan/evu/mem/Mem.scala git_commit= emu \
  -j16 EMU_THREADS=8 EMU_TRACE=fst CONFIG=DefaultExternalVectorMatrixConfig
make -C software/evu git_commit= ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=quanti8mma_evu_example
set -o pipefail
./build/emu -C 20000000 \
  --diff "$NEMU_REPO/build/riscv64-nemu-interpreter-so" \
  -i software/evu/build/quanti8mma_evu_example-riscv64-xs.bin \
  2>&1 | tee run_quanti8mma_evu_diff.log
```

Acceptance requires HIT GOOD TRAP, both unit-scale and varying-scale guest
checks passing, no matrix/store/completion/VRF/CSR mismatch, and no pending
task at the trap. Existing ChiselDB, FST and thread options can be retained
when rebuilding; artifacts must correspond to this hardware configuration.
`-W` forces regeneration of simulation RTL even if synchronized source
timestamps are older than the server's existing generated files.
