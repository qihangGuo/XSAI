# EVU low-precision CVT extension — RTL integration

Status: candidate RTL integration. Baseline: qihangGuo/XSAI `feat/evu-optimization` at `34b9be02b`. The candidate implements all 18 operations through the real EVU backend and imports the YunSuan CVT library. Selector allocation is pending upstream review. Dynamic acceptance and reproducible commands are recorded in `REPRODUCE.md`.

## Decision

Preserve the existing six instructions, their external encodings, RNE semantics, packing and EVCAP[29]. Extend to 16 generic conversion operations plus two BF16 operations: FP32 ↔ I8/U8/E4M3FN/E5M2/E2M1, three float formats ↔ FP32 with given UE8M0 scale, and FP32 ↔ BF16. Reuse generic kernels and dedicated BF16; no one-FU-per-mnemonic requirement.

Keep IEEE FP16↔FP32 and I32↔FP32 on their current EVU paths in this PR. All arithmetic rounding stays RNE at the EVU boundary; YunSuan library supports its documented RM inputs independently. Other rounding modes require a separate public encoding/state proposal, not a silent dependency on scalar FCSR.

The CVT additions introduce no new scale register, descriptor state, TR/SF coupling, mask state or synchronization mechanism. Upstream block PACK retains its scalar control word and vector-stored inverse descriptor. Use current EVU completion/CSR behavior. The meeting's future synchronization/TR integration remains a separate owner decision.

## External envelope and layout

`[31:25] funct7 | [24:20] vs2 | [19:15] vs1 | [14:12] 010 | [11:7] vd | [6:0] 0001011`.

All new conversion forms use public e32/m1; VL counts logical converted values, 0..VLEN/32 (128 at VLEN4096). This is a command configuration, not a claim that source storage is FP32. FP32 uses 4 bytes/value, BF16 uses 2, FP8/I8/U8 use 1, E2M1 uses half a byte. All arrays start at element zero of one physical vector register. Registers remain untyped; opcode selects the format. `vs1=0` required for unary forms; scaled forms use vs1 as a VRF register index, including register0 as valid scale storage.

Reject `vd==vs2`; for scaled forms also reject `vd==vs1`. `vs1==vs2` is legal: both are read-only for this command. Reject nonzero vstart, masking/raw auxiliary mode, wrong encoded/configured width, LMUL other than m1, illegal selector and unsupported capability even at VL0. Valid VL0 has no register or flag effects. No source/destination overlap relaxation in the first integration.

Destinations preserve every byte after `ceil(VL*dstBits/8)`, regardless of tail policy. FP4 even-index element occupies low nibble; odd tail sets unused high nibble in the final written byte to zero. On decode ignore that high nibble. BF16 element i occupies bits16i+15:16i. Active lanes alone contribute EFFLAGS; floating saturation never sets EVXSAT.

## Operation table

See `evu-cvt-isa.json` for machine-readable rows. Mnemonics use destination before source, so `XV_CVT_U8_F32` converts FP32 to U8. Existing `MXFP8` remains the E4M3FN payload spelling; new MXE5M2 uses an explicit name to avoid ambiguity. Optional source-code aliases for MXE4M3 can use the same old opcode, not a new instruction.

| Status | Macro | Conversion | Scale | external f7 | Internal F6 / RS1 | Capability |
|---|---|---|---|---|---|---|
| existing | `XV_CVT_I8_F32` | FP32 → I8 | raw | `0x70` | `0x38` / 0 | bit29 |
| existing | `XV_CVT_E4M3_F32` | FP32 → E4M3FN | raw | `0x71` | `0x39` / 0 | bit29 |
| existing | `XV_CVT_E5M2_F32` | FP32 → E5M2 | raw | `0x72` | `0x3a` / 0 | bit29 |
| existing | `XV_CVT_F32_E4M3` | E4M3FN → FP32 | raw | `0x73` | `0x3b` / 0 | bit29 |
| existing | `XV_CVT_MXFP8_F32` | FP32 → E4M3FN | UE8M0 | `0x74` | `0x3c` / scaleReg | bit29 |
| existing | `XV_CVT_MXFP4_F32` | FP32 → E2M1 | UE8M0 | `0x75` | `0x3d` / scaleReg | bit29 |
| added | `XV_CVT_U8_F32` | FP32 → U8 | raw | `0x27` | `0x38` / 1 | bit31 |
| added | `XV_CVT_F32_I8` | I8 → FP32 | raw | `0x28` | `0x38` / 2 | bit31 |
| added | `XV_CVT_F32_U8` | U8 → FP32 | raw | `0x29` | `0x38` / 3 | bit31 |
| added | `XV_CVT_E2M1_F32` | FP32 → E2M1 | raw | `0x2a` | `0x39` / 1 | bit31 |
| added | `XV_CVT_F32_E5M2` | E5M2 → FP32 | raw | `0x2b` | `0x3a` / 1 | bit31 |
| added | `XV_CVT_F32_E2M1` | E2M1 → FP32 | raw | `0x2c` | `0x3b` / 1 | bit31 |
| added | `XV_CVT_BF16_F32` | FP32 → BF16 | raw | `0x2d` | `0x3b` / 2 | bit33 |
| added | `XV_CVT_F32_BF16` | BF16 → FP32 | raw | `0x2e` | `0x3b` / 3 | bit33 |
| added | `XV_CVT_MXE5M2_F32` | FP32 → E5M2 | UE8M0 | `0x78` | `0x36` / scaleReg | bit32 |
| added | `XV_CVT_F32_MXE4M3` | E4M3FN → FP32 | UE8M0 | `0x79` | `0x32` / scaleReg | bit32 |
| added | `XV_CVT_F32_MXE5M2` | E5M2 → FP32 | UE8M0 | `0x7a` | `0x33` / scaleReg | bit32 |
| added | `XV_CVT_F32_MXFP4` | E2M1 → FP32 | UE8M0 | `0x7b` | `0x34` / scaleReg | bit32 |

EVCAP: bit29 remains exactly the original six; bit30 belongs to upstream BLOCK_AMAX/BLOCK_PACK; bit31 announces all six additional raw generic directions; bit32 announces the four additional MX directions; bit33 announces both BF16 directions. Each CVT capability group is all-or-nothing. Use RV64-safe shifts such as `UINT64_C(1)<<33`. ABI2 and the 13-word snapshot stay unchanged. The new CVT allocation is a proposal for ISA-owner review.

Upstream `34b9be02b` assigns external 0x76/0x77 and internal OPIVV F6 0x3e/0x3f to block AMAX/PACK. The added MX forms use external 0x78..0x7b; MXE5M2 encode uses internal F6 0x36. This avoids both public and private tag collisions. The previous unpublished CVT assignments are superseded.


## Numerical contract

- BF16: RISC-V BF16 1/8/7; FP16 remains IEEE half 1/5/10, never alias the two. BF16 widening is exact for finite inputs, including subnormals; sNaN raises NV and returns canonical qNaN. Narrowing RNE, tininess-after-rounding at BF16 precision with an unbounded exponent, result+NV/OF/UF/NX from the standalone core. Example: FP32 0x007f8000 rounds to BF16 0x0080 but raises UF|NX=0x03, covered by hand-derived tests. Inspecting only the stored result exponent would miss UF.
- I8/U8: conversion of the numeric value with RNE and saturation to [-128,127]/[0,255]. This is not a bit truncation, nor affine quantization. Invalid conversion raises NV; valid inexact raises NX. No EVXSAT. Widening integers to FP32 is exact.
- E4M3FN: 1/4/3, finite maximum448, NaN codes0x7f/0xff, no infinity. E5M2:1/5/2, finite maximum57344, infinities and sNaNs/qNaNs. E2M1:1/2/1, finite maximum6, no NaN/infinity. Preserve established YunSuan numerical behavior, including E2M1 NaN input→positive6+NV and E4M3 finite saturation; do not reinterpret all FP8 overflow policies as one rule.
- Raw decode: finite values exactly expand to FP32. Raw E4M3 NaNs return canonical FP32 qNaN without NV because this format has no sNaN; E5M2 sNaN raises NV. High unused input bits ignored.
- Given-scale encode: output≈input / 2^(factor−127); decode: output≈payload × 2^(factor−127), with final target rounding/flags. Scale is UE8M0, including encoding0=2^-127 and encoding255=NaN; it is not an E4M3 scale. Factor255 follows library format-specific NaN behavior, covered by reference tests. Finite in-range reconstruction is exact, including subnormals: the minimum E5M2 scaled value is 2^-143, above FP32 minimum subnormal 2^-149. Reconstruction overflow sets OF|NX; it does not generate UF.
- Scale producer is software/other ordinary vector instructions. This CVT command consumes `VRF[vs1].element0[7:0]`; it neither computes amax nor generates one scale per block. Separate commands identify separate scale domains.

## Internal lowering and hardware ownership

Internal OPIVV uses opcode0x57, F3=0, vm=1; table above provides exact F6/RS1. Public unary source1 is zero, then lowering inserts the operation subselector. Scaled source1 remains a register index. These are internal private operation tags; software must never issue them as standard RVV instructions.

Use one descriptor table to derive external decode, internal tag, source/destination bits, scaled-source role, capability group and FU routing. Remove arithmetic lowering `(opcode-0x70+0x38)` and numeric range predicates, which fail for the additions. Existing six mappings remain bit-identical.

Implemented provider mapping:

| Host path | Numeric module | Input boundary | Output/core latency | Library vs EVU responsibility |
|---|---|---|---|---|
| Existing fp_xx8 | GenericConvert(raw profile) | io.iss | default2; optional single1 | all raw low formats, packing stays EVU |
| Existing fp_mxfp | GenericConvert(UE8M0 floating profile) | io.iss | default2; optional single1 | MX formats in both directions |
| Added BF16 bypass | NativeBF16Convert | io.iss | 1 | one-cycle issue reservation and matching flags/metadata |
| Existing fp_conv | FPConvBlock/HardFloat | io.pipe(0) | current host3 | standard FP16/32/integer forms |

Local test Saturn starts most new cores at pipe(0), adding one host register. EVU FPLowPrecisionFU starts from io.iss. Therefore do not copy local host depth3 into EVU automatically. Every factory depth, scoreboard/writeback slot, tags, masks and flags must follow the actual entry. Do not add a terminal delay merely for visual uniformity. Multi-latency arbitration must be exercised before selecting BF16 host1.

Preserve two existing raw/MX issue paths initially; this reuses available parallelism. Library profiles prune unused functions. Merging raw and MX into one physical core remains a separate resource/performance decision. Upstream block PACK already shares the MX core: its FP32 multiplier supplies 16 values, then the MX core encodes E2M1 at factor127. PACK owns that input cycle and stalls ordinary MX issue; its result returns to the FMA writeback path two captures later. Mixed PACK/MX/BF16 backend tests check this ownership.

## Sequencer and forwarding changes are mandatory

Current source assumes all narrow lowp outputs use byte width and only F6=0x3d is packed; it only decodes E4M3. Replace those assumptions with sourceBits/destinationBits and direction metadata. Apply the same index transform to reads, writes, write-bank reservations and dependency addresses:

- Input EG uses byte offset floor(i*srcBits/8); output EG uses floor(i*dstBits/8).
- For FP4 decode the source index is floor(i/2) bytes; read nibble offset `(i*4)%DLEN`. Fix source reads as well as destination packing.
- Increment one beat by DLEN/32 logical values for every bridge operation (16 at DLEN512). BF16 uses halfword-packed offsets without falsely claiming m2 register groups.
- Scale source is element0 byte0. The current sequencer captures it once, retains that value across beats, and clears its read intent after capture. An older producer must become visible before capture; a younger overwrite may proceed after capture because the current command uses the retained value. Forwarding requests the exact low byte. Scaled operations are descriptor-selected, including tags0x32..34.
- Producer forwarding masks and consumer masks must represent packed bytes; full FP32 forwarding cannot be reused blindly. The adapter uses the exact packed byte range, including ceil division for an odd FP4 tail; regression runs with forwarding enabled and disabled.
- Completion means all groups and flags have been accounted for; flush/reset must not publish delayed results for a cancelled job. Preserve younger RAW/WAR/WAW and old values in unwritten bytes.

Touchpoints: `EvuDecoder.scala`, `EvuControlSequencer.scala:EvuOpcode`, `EvuLowPrecision.scala`, `EvuIsa.scala`, `EvuVectorBackend.scala`, `backend/ExecuteSequencer.scala`, `backend/Backend.scala`, `common/Diagnostics.scala`, `common/Parameters.scala`, `exu/fp/FPLowPrecision.scala`, capability helper and software intrinsics. The independent numerical backend test does not require NEMU. Full-SoC guest difftest additionally needs matching NEMU opcode/flags support; that is a separate validation gate.

## Composition boundaries

| Requirement | First integration | Reason / later option |
|---|---|---|
| Lowp↔lowp | decode→FP32→encode | explicit rounding and flags; raw finite widening exact; no pairwise FU explosion |
| BF16↔FP16 | BF16↔FP32 + existing FP16 path | software manages the native path's e16/LMUL configuration; not identical to packed e32 ABI |
| NVFP4 block quantization | upstream BLOCK_AMAX/BLOCK_PACK plus software scale preparation; ordinary CVT composition also available | stored scale is E4M3, tensor alpha FP32; UE8M0 consumer is not a native NV command |
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

## Coordinated PRs

1. ISA: [OurCompArchGroup/riscv-matrix-extension-spec, feat-evu](https://github.com/OurCompArchGroup/riscv-matrix-extension-spec/tree/feat-evu) defines external encodings, capabilities, numerical semantics and layout. Its separate CVT proposal must agree with this implementation; internal OPIVV tags and pipeline depths stay here.
2. YunSuan: standalone numeric library, authoritative latency/II and regression, preserving legacy consumer compatibility.
3. XSAI: public decode, descriptor-based sequencing, providers, software intrinsics, backend tests and YunSuan gitlink.

The gitlink must be publicly fetchable before EVU review. Parallel draft review can override the submodule URL locally to the published YunSuan fork; final integration should pin the upstream accepted commit (including a changed SHA after squash merge). Replacing FPConvBlock/HardFloat with YunSuan native IEEE kernels remains a separate per-form equivalence task.

## Review tools

From repository root:

```sh
python3 software/evu/docs/cvt-extension/check_isa.py
python3 software/evu/docs/cvt-extension/check_asm.py
```

The first script checks all 18 descriptor rows, software macros, selector collisions and 270 operand mappings. The second assembles all 18 macros and verifies actual ELF instruction words. `evu_cvt_proposal.h` remains a compatibility include; the macros now live in the normal `evu_intrinsics.h`. Software must query EVCAP before use on other revisions. See `REPRODUCE.md` for RTL execution and export.
