> Follow-up: the compact pipeline task retires fixed-point 0x4c/0x4e and replaces
> the serial implementation. Earlier legacy validation below is historical;
> see [current progress](../evu-quant-pipeline/progress.md).

# EVU Flash Attention implementation plan

Scope: XSAI_external_vector (including CUTE/difftest) and sibling NEMU only.
Target: DefaultExternalVectorMatrixConfig, CUTE 128x128x64, eight banks,
32-byte AB entries, EVU VLEN=4096/DLEN=256, common clock (CDC disabled).
Do not modify nexus-am. Do not run EMU, generate Verilog or guest binaries locally.

## Design

Keep the optimized INT8 Q/K/V, FP32 online-softmax, dynamically quantized INT8 P,
FP32 output algorithm and Q=128/KV=256/D=64 inputs. Add custom0 funct7=0x50:
FP32 source vs2, FP32 inverse scales in rd, scalar rs1 target = (row<<2)|AB-reg.
RNE multiply, RNE add 0.5, clamp to [0,127], RTZ unsigned conversion; pack bytes
into CUTE's native bank/entry layout. Retain fixed-point QuantToCute unchanged.
Check every accepted direct CUTE write against independent NEMU results. Persist
those reference AB bytes across matrix launches so later PV is computed from the
reference P, never adopted from RTL. CUTE result dequantization still uses HBL2.

## Prerequisites

Existing software/evu tests, local nexus-am/tests/exp2test optimized reference,
NEMU riscv64-xs-evu-ref_defconfig; AM_HOME and LLVM set on server.
Local checks: mill -i evuCheck.test.compile xiangshan.test.compile;
make -C ../NEMU -j8 git_commit=; native NEMU regression; RISC-V syntax-only checks.

## Phases

1. Add FP quantizer, routing, target validation, CUTE-write difftest event and
   NEMU arithmetic/write oracle. Compile Scala and rebuild NEMU. Pass: no compile
   errors, legacy native reference regressions still pass, wrong write data,
   wrong target, missing/duplicate writes rejected by new native regression.
2. Add complete Flash Attention guest and independent FP64/quantized goldens,
   use EVU for vector math and both direct matrix paths. Syntax-only compilation
   locally; server builds with:
   make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=flash_attention_evu_test
3. Document server validation and timing. Server builds fresh EMU and NEMU,
   runs new test with difftest; exit criteria: HIT GOOD TRAP, pending=0, nonzero
   checked direct writes, matrix beats and completions, numerical error within
   declared limits. Re-run add/softmax/matrix after RTL changes on server.

## Final verification

Local: Scala compilation, C syntax checks, independent native NEMU regressions,
git diff --check. Server-only full RTL/EMU validation remains explicitly pending;
no performance claims until measured guest end-to-end cycles are available.


## Completion state (2026-10-24)

Local implementation and allowed checks are complete; see progress.md for
results and debugging history. The remaining acceptance is explicitly on the
server. Exact native, behavioral, guest, NEMU and EMU commands, configurations,
accuracy limits and PASS criteria are in
`software/evu/docs/FLASH_ATTENTION.md`. No server result or speedup is claimed.
