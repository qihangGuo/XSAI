# EVU low-precision CVT extension — review draft

Status: proposed encoding and integration contract, not an implemented EVU capability. Baseline: qihangGuo/XSAI `feat/evu-optimization` at c1d87b2ed6e97fd068c0751b40366f760b7e3735. Library candidate: YunSuan standalone CVT PR based on v2r2a f7cced2. Exact selector allocation belongs to the ISA owner; this draft checks the current public decoder and retired opcodes.

## Decision

Preserve the existing six instructions, their external encodings, RNE semantics, packing and EVCAP[29]. Extend to 16 generic conversion operations plus two BF16 operations: FP32 ↔ I8/U8/E4M3FN/E5M2/E2M1, three float formats ↔ FP32 with given UE8M0 scale, and FP32 ↔ BF16. Reuse generic kernels and dedicated BF16; no one-FU-per-mnemonic requirement.

Keep IEEE FP16↔FP32 and I32↔FP32 on their current EVU paths in this PR. All arithmetic rounding stays RNE at the EVU boundary; YunSuan library supports its documented RM inputs independently. Other rounding modes require a separate public encoding/state proposal, not a silent dependency on scalar FCSR.

No new scale register, quantization descriptor, TR/SF coupling, mask state or synchronization mechanism. Use current EVU completion/CSR behavior. The meeting's future synchronization/TR integration remains a separate owner decision.

## External envelope and layout

`[31:25] funct7 | [24:20] vs2 | [19:15] vs1 | [14:12] 010 | [11:7] vd | [6:0] 0001011`.

All new conversion forms use public e32/m1; VL counts logical converted values, 0..VLEN/32 (128 at VLEN4096). This is a command configuration, not a claim that source storage is FP32. FP32 uses 4 bytes/value, BF16 uses 2, FP8/I8/U8 use 1, E2M1 uses half a byte. All arrays start at element zero of one physical vector register. Registers remain untyped; opcode selects the format. `vs1=0` required for unary forms; scaled forms use vs1 as a VRF register index, including register0 as valid scale storage.

Reject `vd==vs2`; for scaled forms also reject `vd==vs1`. `vs1==vs2` is legal: both are read-only for this command. Reject nonzero vstart, masking/raw auxiliary mode, wrong encoded/configured width, LMUL other than m1, illegal selector and unsupported capability even at VL0. Valid VL0 has no register or flag effects. No source/destination overlap relaxation in the first integration.

Destinations preserve every byte after `ceil(VL*dstBits/8)`, regardless of tail policy. FP4 even-index element occupies low nibble; odd tail sets unused high nibble in the final written byte to zero. On decode ignore that high nibble. BF16 element i occupies bits16i+15:16i. Active lanes alone contribute EFFLAGS; floating saturation never sets EVXSAT.

## Operation table

See `evu-cvt-isa.json` for machine-readable rows. Mnemonics use destination before source, so `XV_CVT_U8_F32` converts FP32 to U8. Existing `MXFP8` remains the E4M3FN payload spelling; new MXE5M2 uses an explicit name to avoid ambiguity. Optional source-code aliases for MXE4M3 can use the same old opcode, not a new instruction.

| Status | Macro | Conversion | Scale | proposed/public f7 | Internal F6 / RS1 | Capability |
|---|---|---|---|---|---|---|
| existing | `XV_CVT_I8_F32` | FP32 → I8 | raw | `0x70` | `0x38` / 0 | bit29 |
| existing | `XV_CVT_E4M3_F32` | FP32 → E4M3FN | raw | `0x71` | `0x39` / 0 | bit29 |
| existing | `XV_CVT_E5M2_F32` | FP32 → E5M2 | raw | `0x72` | `0x3a` / 0 | bit29 |
| existing | `XV_CVT_F32_E4M3` | E4M3FN → FP32 | raw | `0x73` | `0x3b` / 0 | bit29 |
| existing | `XV_CVT_MXFP8_F32` | FP32 → E4M3FN | UE8M0 | `0x74` | `0x3c` / scaleReg | bit29 |
| existing | `XV_CVT_MXFP4_F32` | FP32 → E2M1 | UE8M0 | `0x75` | `0x3d` / scaleReg | bit29 |
| proposed | `XV_CVT_U8_F32` | FP32 → U8 | raw | `0x27` | `0x38` / 1 | bit30 |
| proposed | `XV_CVT_F32_I8` | I8 → FP32 | raw | `0x28` | `0x38` / 2 | bit30 |
| proposed | `XV_CVT_F32_U8` | U8 → FP32 | raw | `0x29` | `0x38` / 3 | bit30 |
| proposed | `XV_CVT_E2M1_F32` | FP32 → E2M1 | raw | `0x2a` | `0x39` / 1 | bit30 |
| proposed | `XV_CVT_F32_E5M2` | E5M2 → FP32 | raw | `0x2b` | `0x3a` / 1 | bit30 |
| proposed | `XV_CVT_F32_E2M1` | E2M1 → FP32 | raw | `0x2c` | `0x3b` / 1 | bit30 |
| proposed | `XV_CVT_BF16_F32` | FP32 → BF16 | raw | `0x2d` | `0x3b` / 2 | bit32 |
| proposed | `XV_CVT_F32_BF16` | BF16 → FP32 | raw | `0x2e` | `0x3b` / 3 | bit32 |
| proposed | `XV_CVT_MXE5M2_F32` | FP32 → E5M2 | UE8M0 | `0x76` | `0x3e` / scaleReg | bit31 |
| proposed | `XV_CVT_F32_MXE4M3` | E4M3FN → FP32 | UE8M0 | `0x77` | `0x32` / scaleReg | bit31 |
| proposed | `XV_CVT_F32_MXE5M2` | E5M2 → FP32 | UE8M0 | `0x78` | `0x33` / scaleReg | bit31 |
| proposed | `XV_CVT_F32_MXFP4` | E2M1 → FP32 | UE8M0 | `0x79` | `0x34` / scaleReg | bit31 |

EVCAP proposal: bit29 remains exactly the original six; bit30 announces all six additional raw generic directions; bit31 announces the four additional MX directions; bit32 announces both BF16 directions. Each capability group is all-or-nothing: set a bit only when all members have working decode/model/RTL and pass their acceptance; if a deploy-time profile removes a member, disable the entire group and reject every opcode in that group. Existing deployments retain bit29. Use RV64-safe `UINT64_C(1)<<32`. EVCAP[31:30] and [32] currently unused in the checked capability generator; ISA owner must confirm external reservations. ABI2 and the 13-word snapshot stay unchanged because this adds no architectural state.

## Numerical contract

- BF16: RISC-V BF16 1/8/7; FP16 remains IEEE half 1/5/10, never alias the two. BF16 widening is exact for finite inputs, including subnormals; sNaN raises NV and returns canonical qNaN. Narrowing RNE, tininess-after-rounding at BF16 precision with an unbounded exponent, result+NV/OF/UF/NX from the standalone core. Example: FP32 0x007f8000 rounds to BF16 0x0080 but raises UF|NX=0x03, covered by hand-derived tests. Inspecting only the stored result exponent would miss UF.
- I8/U8: conversion of the numeric value with RNE and saturation to [-128,127]/[0,255]. This is not a bit truncation, nor affine quantization. Invalid conversion raises NV; valid inexact raises NX. No EVXSAT. Widening integers to FP32 is exact.
- E4M3FN: 1/4/3, finite maximum448, NaN codes0x7f/0xff, no infinity. E5M2:1/5/2, finite maximum57344, infinities and sNaNs/qNaNs. E2M1:1/2/1, finite maximum6, no NaN/infinity. Preserve established YunSuan numerical behavior, including E2M1 NaN input→positive6+NV and E4M3 finite saturation; do not reinterpret all FP8 overflow policies as one rule.
- Raw decode: finite values exactly expand to FP32. Raw E4M3 NaNs return canonical FP32 qNaN without NV because this format has no sNaN; E5M2 sNaN raises NV. High unused input bits ignored.
- Given-scale encode: output≈input / 2^(factor−127); decode: output≈payload × 2^(factor−127), with final target rounding/flags. Scale is UE8M0, including encoding0=2^-127 and encoding255=NaN; it is not an E4M3 scale. Factor255 follows library format-specific NaN behavior, covered by reference tests. Very small scaled decode may produce FP32 subnormals/inexact flags; it is not always an exact shift.
- Scale producer is software/other ordinary vector instructions. This CVT command consumes `VRF[vs1].element0[7:0]`; it neither computes amax nor generates one scale per block. Separate commands identify separate scale domains.

## Internal lowering and hardware ownership

Internal OPIVV uses opcode0x57, F3=0, vm=1; table above provides exact F6/RS1. Public unary source1 is zero, then lowering inserts the operation subselector. Scaled source1 remains a register index. These are internal private operation tags; software must never issue them as standard RVV instructions.

Use one descriptor table to derive external decode, internal tag, source/destination bits, scaled-source role, capability group and FU routing. Remove arithmetic lowering `(opcode-0x70+0x38)` and numeric range predicates, which fail for the additions. Existing six mappings remain bit-identical.

Deployment recommendation:

| Host path | Numeric module | Input boundary | Output/core latency | Library vs EVU responsibility |
|---|---|---|---|---|
| Existing fp_xx8 | GenericConvert(raw profile) | io.iss | default2; optional single1 | all raw low formats, packing stays EVU |
| Existing fp_mxfp | GenericConvert(UE8M0 floating profile) | io.iss | default2; optional single1 | MX formats in both directions |
| Added BF16 bypass | NativeBF16Convert | io.iss | 1 | one-cycle issue reservation and matching flags/metadata |
| Existing fp_conv | FPConvBlock/HardFloat | io.pipe(0) | current host3 | standard FP16/32/integer forms |

Local test Saturn starts most new cores at pipe(0), adding one host register. EVU FPLowPrecisionFU starts from io.iss. Therefore do not copy local host depth3 into EVU automatically. Every factory depth, scoreboard/writeback slot, tags, masks and flags must follow the actual entry. Do not add a terminal delay merely for visual uniformity. Multi-latency arbitration must be exercised before selecting BF16 host1.

Preserve two existing raw/MX issue paths initially; this reuses available parallelism. Library profiles prune unused functions. Merging them into one physical shared core is a separate resource/performance decision, with arbitration and contention measurements.

## Sequencer and forwarding changes are mandatory

Current source assumes all narrow lowp outputs use byte width and only F6=0x3d is packed; it only decodes E4M3. Replace those assumptions with sourceBits/destinationBits and direction metadata. Apply the same index transform to reads, writes, write-bank reservations and dependency addresses:

- Input EG uses byte offset floor(i*srcBits/8); output EG uses floor(i*dstBits/8).
- For FP4 decode the source index is floor(i/2) bytes; read nibble offset `(i*4)%DLEN`. Fix source reads as well as destination packing.
- Increment one beat by DLEN/32 logical values for every bridge operation (16 at DLEN512). BF16 uses halfword-packed offsets without falsely claiming m2 register groups.
- Scale source always element0 byte0; keep read intent until command tail. Younger overwrites must wait. When using the latest byte-mask forwarding, request/check exactly the needed byte; no direct unchecked shortcut. Replace `funct6>=0x3c` tests because new scaled decode tags0x32..34 are lower.
- Producer forwarding masks and consumer masks must represent packed bytes; full FP32 forwarding cannot be reused blindly. Start conservatively by excluding new packed-source decoding from fast forwarding unless its exact mask/index is verified. Current upstream changes to forwarding require re-audit; don't copy the previous blanket policy.
- Completion means all groups and flags have been accounted for; flush/reset must not publish delayed results for a cancelled job. Preserve younger RAW/WAR/WAW and old values in unwritten bytes.

Touchpoints: `EvuDecoder.scala`, `EvuControlSequencer.scala:EvuOpcode`, `EvuLowPrecision.scala`, `EvuIsa.scala`, `EvuVectorBackend.scala`, `backend/ExecuteSequencer.scala`, `backend/Backend.scala`, `common/Diagnostics.scala`, `common/Parameters.scala`, `exu/fp/FPLowPrecision.scala`, capability helper and software intrinsics. NEMU opcode legality/data/flags, guest tests and difftest are companion changes; identify/pin a current NEMU branch before RTL acceptance.

## Composition boundaries

| Requirement | First integration | Reason / later option |
|---|---|---|
| Lowp↔lowp | decode→FP32→encode | explicit rounding and flags; raw finite widening exact; no pairwise FU explosion |
| BF16↔FP16 | BF16↔FP32 + existing FP16 path | software manages the native path's e16/LMUL configuration; not identical to packed e32 ABI |
| NVFP4 block quantization | existing RMSNorm/amax/scale sequence + raw E2M1 encode | stored scale is E4M3, tensor alpha FP32; UE8M0 consumer is not a native NV command |
| Optional NvPayloadPipeline | library-only candidate; no opcode in this draft | input needs prepared inverse or saved E4M3 scale+alpha; port/context contract not finalized |
| Affine I8/U8 | FP32 divide/multiply + defined round/zero-point/clamp sequence | preserve reference rounding order; cannot rename it to plain I8 cast |
| I4/U4 | no architectural capability yet | current IntegerToFP32(4) is a building block; encode/packing/flags ABI unfinished |
| FP64 | disabled in production EVU | current max SEW32; local full Saturn54-form validation does not change EVU scope |

## Acceptance plan

1. Machine schema: all18 rows, six legacy words unchanged, pairwise internal tag uniqueness, holes/retired selector check. Current static checker270 cases; not RTL evidence.
2. YunSuan standalone numerical core tests: both pipeline choices, all formats/directions, five RM library modes, special values, scaled decode range, bubbles/reset, independent reference anchors.
3. EVU decode/legality: full selector/capability map, unary nonzero vs1, invalid widths/LMUL/mask/aliases/VL0, reserved operations still trap.
4. EVU FU streaming: actual io.iss boundary, mixed-depth writeback, randomized operands and adjacent old6 operations, numerical outputs and active flags, no loss/duplicate completion.
5. Backend dependency/layout: VL0/1/15/16/17/127/128; odd FP4; source read crossing byte/nibble/EG; packed destination overlap preservation; scale producer RAW and younger overwrite WAR; forwarding enabled/disabled; tails and flush.
6. Reference/guest: matching NEMU, old88-case lowp smoke plus new directions, conversion→compute→conversion sequences; full SoC smoke after core/backend gates. Do not equate library PASS with production EVU PASS.

## PR sequence

A. YunSuan standalone library and regression; preserve legacy two-cycle interfaces and specify short bypass latency.
B. EVU ISA/spec/header/model contract; proposed selectors reviewed before enabling capabilities.
C. EVU provider wiring, descriptor-based sequencing, backend tests and submodule pin to public library commit; old6 regression and new directional coverage.
D. Optional provider replacement of current FPConvBlock using YunSuan native kernels, only after per-form numeric/lane/control equivalence; independent from packed-lowp completion.

## Review tools

From repository root:

```sh
python3 software/evu/docs/cvt-extension/check_isa.py
python3 software/evu/docs/cvt-extension/check_asm.py
```

The first script checks the baseline decoder, enum holes and270 operand cases.
The second assembles all18 macros using Clang/RV64 and verifies actual ELF words.
`evu_cvt_proposal.h` is an opt-in review artifact in this directory, not a
production include; it requires `XV_ENABLE_CVT_EXTENSION_PROPOSAL`. No new
decoder capability is enabled. All allocation numbers remain proposals.
