# Scalar-offset slides and fence-only synchronization

The public slide selectors remain 0x3e/0x3f, with SEW=8/16/32. Source1 is
now a scalar GPR containing an unsigned 64-bit offset. The adapter emits
standard Saturn `vslideup.vx` / `vslidedown.vx`, supplies the captured GPR
value through `rs1_data`, and does not reserve a VRF source1 group.
Slideup requires disjoint destination/source groups; slidedown permits an
identical group. Width/group/overlap checks still apply at VL=0. No new FU
is introduced and the configuration/context ABI remains version 2.

WAIT is removed from CPU decoding, EVU command decoding/control and NEMU.
Its selector 0x19 is reserved and illegal at every VL. `XV_WAIT()` is
removed rather than silently aliased; rebuild callers with `XV_FENCE()`.
The virtual-memory test now uses FENCE before observing EVU writes.
EVU register dependencies are handled by the existing hardware scoreboard;
software only needs completion synchronization at CPU/shared-memory
boundaries. There is no public job-token interface to justify per-task WAIT.

## Server validation

Synchronize XSAI and NEMU separately and rebuild both. From the XSAI root:

```sh
export NOOP_HOME="$PWD"
export NEMU_HOME="$(cd ../NEMU_evu && pwd)"
make -C "$NEMU_HOME" git_commit= riscv64-xs-evu-ref_defconfig
make -C "$NEMU_HOME" git_commit= clean
make -C "$NEMU_HOME" git_commit= -j8
bash software/evu/scripts/check_slide_ref.sh "$NEMU_HOME"
set -o pipefail
make emu CONFIG=DefaultExternalVectorMatrixConfig ACCELERATOR_CDC_ENABLE=0 \
  EMU_THREADS=8 EMU_TRACE=fst -j16 2>&1 | tee xsai_build.log
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=slide_evu_test
./build/emu --diff "$NEMU_HOME/build/riscv64-nemu-interpreter-so" \
  -C 100000000 -i software/evu/build/slide_evu_test-riscv64-xs.bin
```

The reference, actual Saturn backend and guest test each cover 216 directed
value cases across SEW=8/16/32 and m1/m2/mf2, zero/full VL, preserved tails,
cross-DLEN/register motion, offsets at and beyond VLMAX, 2^32 and UINT64_MAX,
and in-place slidedown. The backend also checks completion backpressure,
illegal groups/overlap, retired WAIT and reuse after flush. The decoder
test checks the exact OPIVX encoding and full-width offset retention.
The reference tests x0 and an odd scalar GPR under m2; the guest checks
neighbor registers, output guards, preserved VL/VTYPE and sticky flags.

Require `[SLIDE] PASS cases=216 errors=0`, GOOD TRAP and no assertion or
difftest failure. Then rerun `virtual_memory_evu_test`, `vector_add_evu_test`,
`rvv_math_evu_test`, `low_precision_evu_test`, and the existing Softmax and
RMSNorm/NVFP4 tests. Keep the old performance configuration for comparison.

Local checks are limited to syntax/type checking and documentation builds.
RTL simulation, emulator execution and binary builds must run on the server;
the new regression sources do not by themselves establish a passing run.
