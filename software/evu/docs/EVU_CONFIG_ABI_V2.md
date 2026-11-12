# EVU configuration ABI 2

Date: 2026-11-09. Source complete; Scala/RTL integration, numerical and performance
regressions require server execution. This ABI intentionally breaks compatibility
with old configuration instructions, context buffers and the old reference library.

## Changes

EQDESC0..7 (0x8d0..0x8d7) and their 408 state bits are removed. Access now raises
illegal instruction. The software context no longer saves descriptors and carries
`XV_CONTEXT_MAGIC`. EVCAP low byte is 2, descriptor count bits [15:8] are zero,
and bit 27 advertises returned-VL configuration. Other existing capability bits
are unchanged. The difftest CSR payload shrinks from 21 to 13 words, with EVCFG
at index 12; no reserved descriptor padding is retained. Rebuild difftest C++
along with emu and use the matching NEMU library.

| Instruction | Encoding | Inputs and result |
| --- | --- | --- |
| xv.setvli | custom0, funct7=0x45 | AVL=x[rs1]; immediate VTYPE=instr[14:12]:instr[24:20]; x[rd]=actual VL |
| xv.setvl | custom0, funct7=0x46, funct3=7 | AVL=x[rs1]; full VTYPE=x[rs2]; x[rd]=actual VL |

Other funct3 values for setvl are illegal. VTYPE uses low-byte fields LMUL[2:0],
SEW[5:3], VTA[6], VMA[7]. Unsupported SEW/LMUL or nonzero high VTYPE bits produce
VILL=1 and VL=0. AVL is unsigned 64-bit and clamps to VLMAX, without truncating
before comparison. AVL=0 is empty, UINT64_MAX requests VLMAX, rs1=x0 means zero,
and rd=x0 discards only the result. These custom instructions do not implement
RVV's special zero-register keep-VL/VLMAX forms.

`xv_setvl(avl, type)` is a C inline using the register form;
`xv_setvli(avl, constant_type)` emits the immediate form. Both return uint32_t VL
without a CSR read. The immediate helper uses `.insn i` with a signed 12-bit
encoding; it needs no assembler support for the custom mnemonic.

EVCFG remains readable subject to EVU enable/privilege checks. Its write entry
is M-mode only, for restoring the entire normalized VL/VTYPE/Configured/VILL
state through `xv_restore_config`. There is no ABI-1 write fallback in the
normal configuration API. Enabled U/S code uses the new configuration instructions.
CSR accesses retain drain protection, including EVCFG reads and restoration writes.

The scalar functional unit computes returned VL with the same normalization
helper used by the authoritative CSR bank. The normalized config travels with
the command; Backend preserves it instead of replacing it with the old snapshot.
CSR state changes only after scalar retirement. Configuration instructions retain
noSpec/blockBack and status-completion ordering. rd=rs1/rs2 reads the old inputs
before writing the result. NEMU publishes scalar rd immediately and never rewrites
it when the queued EVU completion arrives.

Only EVU-enabled configurations add the scalar result routing and second scalar
source to MRELEASE/Evu. The extra source shares IntRD(1,2); returned VL shares
IntWB(3,2). No physical RF port is added and existing priorities remain unchanged.
HBL2, CUTE and non-EVU-enabled topology are not changed. These are integration
changes to EVU-specific CPU paths, beyond the previous EVU-local optimization.

## Server rebuild

Synchronize the current `XSAI_external_vector/` tree including its modified
`difftest/` submodule files, and synchronize local `NEMU/` into server `NEMU_evu/`.
No nexus-am files were changed by this ABI update.

In `NEMU_evu`:

```sh
make git_commit= riscv64-xs-evu-ref_defconfig
make git_commit= clean
make git_commit= -j8
make -C tools/evu-ref-test NEMU_HOME=../..
tools/evu-ref-test/evu_compute_ref_test build/riscv64-nemu-interpreter-so
```

In `XSAI_external_vector`, rebuild emu with the usual command:

```sh
make emu CONFIG=DefaultExternalVectorMatrixConfig \
  ACCELERATOR_CDC_ENABLE=0 EMU_THREADS=8 EMU_TRACE=fst -j16 2>&1 | tee xsai_build.log
```

Rebuild each guest test with the existing toolchain; for example:

```sh
make -C software/evu ARCH=riscv64-xs CROSS_COMPILE= \
  CC="clang --target=riscv64 -Xclang -target-feature -Xclang +zvamesfexp2 -Xclang -target-feature -Xclang +zvamesfexp2bf16" \
  AS="clang --target=riscv64 -Xclang -target-feature -Xclang +zvamesfexp2 -Xclang -target-feature -Xclang +zvamesfexp2bf16" \
  LD=ld.lld AR=llvm-ar OBJCOPY=llvm-objcopy \
  OBJDUMP="llvm-objdump --mattr=+zvamesfexp2" READELF=llvm-readelf \
  MARCH=rv64gcv NAME=online_softmax_evu_test
```

Also rebuild/run `NAME=csr_context_evu_test` before the performance tests and
rebuild the FlashAttention variant being measured. The ordinary build rules track
header changes; ensure the selected bin actually comes from this synchronized tree.

```sh
./build/emu \
  --diff /nfs/home/guoqihang/workspace/NEMU_evu/build/riscv64-nemu-interpreter-so \
  -C 100000000 \
  -i software/evu/build/online_softmax_evu_test-riscv64-xs.bin \
  -- +evu_mem_trace=1 2>&1 | tee evu_softmax_contexts_abi2.log
```

Use `summarize_softmax_fences.py` as documented in EVU_FMA_CONTEXT_DIAGNOSTICS.md.
The new configuration sequence changes software instruction counts; compare
one/two ordinary FMA contexts using the SAME new bin to attribute scheduling
benefit. Do not attribute the entire delta from the old 6652/5353-cycle bin to
the extra context alone.

## Tests and remaining validation

Added server-side test sources:

```sh
mill -i evuCheck.test.testOnly evu.EvuConfigRulesSpec
mill -i evuMmuCheck.test.testOnly xiangshan.evucheck.EvuConfigCSRSpec
mill -i evuMmuCheck.test.testOnly xiangshan.evucheck.EvuRobOrderingSpec
```

Coverage includes all 256 VTYPE bytes, zero/large AVL, high-bit rejection,
scalar input aliases, rd=x0, no duplicate scalar completion write, removed
CSR faults, M-only EVCFG writes, normalized context restore and config/result
agreement under backpressure. Existing ordering/redirect, softmax, EXP2,
memory/FENCE and FlashAttention regressions still apply. Run EVU-disabled
configuration elaboration too. Passing source checks does not establish RTL
correctness or timing.

Local checks completed without bins: GCC syntax-only for NEMU model, compute,
CSR permission code and native reference tests; Clang RISC-V syntax-only for
CSR/context, softmax and FlashAttention guests; native FlashAttention syntax;
LLVM textual assembly encoding of both new forms; Python log-parser tests;
`git diff --check`. A clang-18 `-cc1as -filetype null` check crashed in the tool;
the replacement `llvm-mc -filetype=asm -show-encoding` succeeded without an
object file. No Java/Mill, RTL generation, emu run or guest/native bin build was
performed locally. Hardware compile/regression, end-to-end correctness, cycles
and PPA remain pending server results.
