# EVU RVV math extension

## Interface

The new commands use custom0 (`0x0b`) and map to ordinary RVV OP-V encodings
inside `EvuRvvMath.encode`. They reuse Saturn's existing units, issue queues,
VRF and completion path. No CPU scalar result interface or new arithmetic
unit is introduced. The RVV math capability is EVCAP bit 28; CSR ABI and the
13-word snapshot remain version 2. Retired opcodes remain illegal.

| funct7 | Software macro | Saturn operation | Public SEW |
| --- | --- | --- | --- |
| 0x60 | XV_FSQRT | vfsqrt.v | 16, 32 |
| 0x61 | XV_FRSQRT7 | vfrsqrt7.v | 16, 32 |
| 0x62 | XV_FSQRT_E0 | vfsqrt.v, local VL=1 | 32/m1 |
| 0x63 | XV_FRSQRT7_E0 | vfrsqrt7.v, local VL=1 | 32/m1 |
| 0x64 | XV_FREC7 | vfrec7.v | 16, 32 |
| 0x65 | XV_FREC7_E0 | vfrec7.v, local VL=1 | 32/m1 |
| 0x66 | XV_FSGNJX | vfsgnjx.vv | 16, 32 |
| 0x67 | XV_FWCVT_F_F | vfwcvt.f.f.v | 16 -> 32 |
| 0x68 | XV_FNCVT_F_F | vfncvt.f.f.w | 16, source is 32 |
| 0x69 | XV_FCVT_X_F | vfcvt.x.f.v | 32 |
| 0x6a | XV_FCVT_F_X | vfcvt.f.x.v | 32 |
| 0x6b | XV_NCLIP_IMM | vnclip.wi | 8 or 16, source is twice SEW |
| 0x6c | XV_ZEXT2 | vzext.vf2 | 16 or 32, source is half SEW |
| 0x6d | XV_SEXT2 | vsext.vf2 | 16 or 32, source is half SEW |
| 0x6e | XV_NSRL_IMM | vnsrl.wi | 8 or 16, source is twice SEW |

The custom envelope's rs1 field must be zero for unary operations. For the
two `.wi` operations it carries an unsigned five-bit shift, not a VRF source.
For sign injection it names the sign source. `XV_FABS(vd, vs, sew)` is a
sign-XOR alias using the same source twice and consumes no additional opcode.

FP operations use the EVU's existing fixed RNE mode, independent of CPU FCSR.
`vfrsqrt7` and `vfrec7` preserve the RVV estimate semantics; they are not
correctly rounded full-precision replacements for sqrt/divide. `vnclip.wi`
uses the existing fixed VXRM=0 (RNU), including its discarded-bit rounding,
then signed saturation and EVXSAT. Shift zero performs saturation without
rounding and is suitable after `vfcvt.x.f.v` RNE conversion. `vnsrl.wi` never
sets EVXSAT. Sign injection does not raise floating-point exceptions, even
for signaling-NaN bit patterns.

Widening uses twice the destination EMUL; narrowing uses twice the source
EMUL; extension reads half the source EMUL. The custom legality checks and
Saturn hazard masks agree on these widths, including fractional LMUL. EMUL
above 8, below 1/8 for extension, group misalignment/out-of-range and forbidden
overlap trap, including VL=0. Widening overlap is permitted only in the high
part when the source EMUL is at least 1; narrowing overlap is permitted at
the low part. Unused rs1 fields do not acquire VRF dependencies.

E0 forms leave global VL/VTYPE unchanged, preserve other elements, and do no
work at VL=0. This extension follows the existing EVU ABI's ordinary unmasked
commands; it does not introduce general mask or vstart programming support.
All retained results and flags travel through normal command completion.

The backend's sign-injection flag path now suppresses the unused comparison
unit's exception flags. Without this change an sNaN bit-pattern operation
could incorrectly report NV.

## Server build and acceptance

Synchronize both the XSAI sources and the matching local `NEMU` sources to
the server's `NEMU_evu` checkout. The XSAI rsync alone does not include NEMU.
Preserve server Git metadata and exclude build artifacts when copying.
Run all builds, Verilog generation, native/guest binaries and RTL simulation
on the server. From the server XSAI root:

```sh
export NOOP_HOME="$PWD"
export NEMU_HOME="$(cd ../NEMU_evu && pwd)"
make -C "$NEMU_HOME" git_commit= riscv64-xs-evu-ref_defconfig
make -C "$NEMU_HOME" git_commit= clean
make -C "$NEMU_HOME" git_commit= -j8
bash software/evu/scripts/check_rvv_math_ref.sh "$NEMU_HOME"
set -o pipefail
make emu CONFIG=DefaultExternalVectorMatrixConfig ACCELERATOR_CDC_ENABLE=0 \
  EMU_THREADS=8 EMU_TRACE=fst -j16 2>&1 | tee xsai_build.log
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=rvv_math_evu_test
./build/emu --diff "$NEMU_HOME/build/riscv64-nemu-interpreter-so" \
  -C 100000000 -i software/evu/build/rvv_math_evu_test-riscv64-xs.bin
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=rmsnorm_nvfp4_evu_test
./build/emu --diff "$NEMU_HOME/build/riscv64-nemu-interpreter-so" \
  -C 100000000 -i software/evu/build/rmsnorm_nvfp4_evu_test-riscv64-xs.bin \
  2>&1 | tee rmsnorm_nvfp4.log
python3 software/evu/scripts/summarize_rmsnorm_nvfp4.py rmsnorm_nvfp4.log > rmsnorm_nvfp4.csv
```

`check_rvv_math_ref.sh` first builds the host reference test, checks known
numerical results and generates a trace. A separate Scala invocation replays
the trace through the actual EVU/Saturn backend. The 73-case trace covers all 15
encodings, VL=0 and tails, m1/m2/mf2, FP16/FP32 sqrt/estimates, signed zeros,
NaNs/infinities/subnormals, sign injection, conversions, 8/16-bit narrowing,
extension, flags and saturation. Reference negative tests exercise illegal
widths/groups/overlaps at VL=0. The decode test also checks unary reserved
fields and standard RVV encoding constants under backpressure.
The backend test holds completion backpressure, checks an in-place E0 sqrt,
and verifies reuse after an idle flush.

The guest smoke test has 54 directed cases and prints one PASS line unless
a check fails. It verifies four destination registers and surrounding output
guards, VL/VTYPE preservation and EVU flags. This exercises CPU dispatch and
the full SoC, which a backend-only trace cannot validate. Require GOOD TRAP,
no assertion/difftest failure, and zero outstanding EVU work.

After passing the new tests, rerun `vector_add_evu_test`, `exp2_evu_test`,
`csr_context_evu_test`, and `online_softmax_evu_test` (correctness with
`EVU_SOFTMAX_CHECK=1`, then the same performance configuration as baseline).
The changes affect shared group-hazard handling, so fractional/multiple LMUL
coverage and existing arithmetic regression are required before acceptance.

## Validation status

Before the user requested server-only binary/RTL validation, the decode suite
passed two tests and the first 54-case NEMU math test passed. The backend
simulation was stopped during Verilator compilation at that request; no
successful backend execution is claimed. Expanded special-value/byte-width
and illegal-group cases were added afterward and have not been executed.
The final sign-injection and test-harness corrections still require server
compilation and execution. Local follow-up is limited to source review,
C syntax-only checks, parser-only Python tests, shell syntax and whitespace.

## Previous softmax baseline

The supplied `evu_softmax_512_before.log` ends with GOOD TRAP. Its header is
rows=32, cols=256, stride=256, wave4x2, warmup=1, check=0, diagnostics=1.
The 512 in the filename corresponds to DLEN/MLEN=512, not 512 columns.
The recorded configuration has VLEN=4096, one ordinary FMA context and two
reduction contexts. Formal guest measurements are:

| Mode | Cycles | Instructions | Elements/cycle |
| --- | ---: | ---: | ---: |
| first=0 | 6692 | 1211 | 1.224 |
| first=1 | 5444 | 966 | 1.504 |

Both print err=0 and fl=1 (inexact). FENCE intervals are 2:3 and 9:10.
Guest numerical checks were disabled, so this is a performance baseline,
not evidence that the numerical-check variant passed. It predates these
15 new instructions and is not an RMSNorm or NVFP4 result.
