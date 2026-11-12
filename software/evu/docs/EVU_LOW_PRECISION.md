# EVU low-precision conversion extension

Six custom0 instructions use funct3=2 and public e32/m1 configuration.
EVCAP bit29 advertises the extension; ABI2 and its 13-word snapshot remain.
The raw instruction's source1 must be zero for unary operations. Sources
and destination must not overlap; MXFP also forbids destination=scale source.
VL counts logical elements, from 0 to VLEN/32. Other LMULs, nonzero vstart,
raw auxiliary modes/masks and wrong widths are rejected, including at VL=0.
Valid VL=0 does not change data or flags. Only active output bytes are written;
the rest of the destination register is preserved regardless of tail policy.

| funct7 | Software macro | Source / destination layout |
| --- | --- | --- |
| 0x70 | XV_CVT_I8_F32(vd, vs) | FP32 elements -> contiguous signed INT8 bytes |
| 0x71 | XV_CVT_E4M3_F32(vd, vs) | FP32 elements -> contiguous E4M3 bytes |
| 0x72 | XV_CVT_E5M2_F32(vd, vs) | FP32 elements -> contiguous E5M2 bytes |
| 0x73 | XV_CVT_F32_E4M3(vd, vs) | contiguous E4M3 bytes -> FP32 elements |
| 0x74 | XV_CVT_MXFP8_F32(vd, scale, vs) | FP32 elements / UE8M0 scale -> E4M3 bytes |
| 0x75 | XV_CVT_MXFP4_F32(vd, scale, vs) | FP32 elements / UE8M0 scale -> packed E2M1 |

These are private extensions, not claims of standard RVV encodings. The
Saturn adapter uses internal OPIVV funct6 0x38..0x3d exclusively for these
operations; the six public instructions remain custom0 funct7 0x70..0x75.
Retired opcodes stay reserved. No BF16 or FP64 capability is added.

MXFP reads the low byte of scale[0], held for all micro-ops in the command.
Its value is the UE8M0 exponent encoding: factor=127 means scale=1. A factor
of 255 is NaN. It is not an E4M3 code. For NVFP4, software computes z using
alpha and the decoded E4M3 block scale, then converts z with factor=127.
FP4 stores the earlier element in the low nibble; an odd tail clears the
unused high nibble of its last byte. No separate pack instruction is needed.

## Numerical behavior

Rounding is fixed RNE, independent of the scalar FCSR. INT8 uses signed
[-128,127] saturation with NV for invalid conversion and NX for inexact valid
conversion. E4M3 saturates at magnitude448. E5M2 uses its infinity encoding
on RNE overflow. MXFP4 saturates at magnitude6; NaN becomes positive6 with
NV. MXFP8 uses E4M3 finite saturation. Overflow/inexact flags follow YunSuan;
floating saturation does not set EVXSAT. All inactive lanes are excluded
from flag accumulation.

E4M3 decoding is exact for every finite code, including signed zero and
subnormals. Codes 0x7f/0xff decode to canonical FP32 qNaN without flags;
this format has no separate signaling-NaN encoding. Normalized scales are
decoded from their saved, rounded E4M3 code, not from the FP32 candidate.

## Backend organization

`FPXX8Factory` and `FPMXFPFactory` are separate `SplitFP` issue paths named
`fp_xx8` and `fp_mxfp`. Each has one ordinary execution context and a
two-stage conversion FU. XX8 instantiates CVT_xx8 plus E4M3 decoding;
MXFP instantiates CVT_mxfp. DLEN/32 parallel converters process one FP32
element group. Pipeline latency is not whole-vector or whole-kernel latency.

The sequencer uses separate source/destination widths. FP4 destination
addressing divides the element index by two. All write-bank reservations,
pipeline hazards and final writes use that same packed address. Packed
destinations remain reserved until the command tail; scale reads remain
protected through all micro-ops. Current low-precision paths use VRF writes,
not the existing full-width vector-result forwarding fast path.

Results share the existing physical banked VRF and write arbitration with
other units. Independent issue does not guarantee conflict-free overlap.
Diagnostics add per-FU, per-read-path and operation-class counters for XX8
and MXFP. Existing FMA/exp2 queues and arithmetic units remain separate.

## Server-only validation

Synchronize XSAI sources and sibling local NEMU separately to the server's
XSAI and NEMU_evu checkouts. Do not copy local build/out artifacts. Preserve
matching old EMU/NEMU shared objects before rebuilding if a same-image
before/after comparison is desired.

```sh
export NOOP_HOME="$PWD"
export NEMU_HOME="$(cd ../NEMU_evu && pwd)"
make -C "$NEMU_HOME" git_commit= riscv64-xs-evu-ref_defconfig
make -C "$NEMU_HOME" git_commit= clean
make -C "$NEMU_HOME" git_commit= -j8
bash software/evu/scripts/check_low_precision_ref.sh "$NEMU_HOME"
set -o pipefail
make emu CONFIG=DefaultExternalVectorMatrixConfig ACCELERATOR_CDC_ENABLE=0 \
  EMU_THREADS=8 EMU_TRACE=fst -j16 2>&1 | tee xsai_build.log
bash software/evu/scripts/check_low_precision_emu.sh "$NEMU_HOME"
```

The NEMU --lowp test checks 14 independent known results and emits 198
trace cases over six operations, VL=0, odd tails, EG/register boundaries,
three scale/input modes, signed zeros, subnormals, infinities and NaNs.
Illegal width/LMUL/alias/unary cases are tested at VL=0. The Scala backend
replay checks destination and neighboring registers, flags and completion
backpressure. The guest smoke test has 88 cases with independent expected
values and checks data, flags, VL/config preservation and store guards.

Local validation passed Scala parser and typer-only checks against cached
dependencies (no class emission or elaboration), C syntax-only checks,
36 Python parser/comparison tests and shell syntax.
The new RTL execution, 198-case reference execution, guest tests and performance
measurements have not been run locally or on the server in this change.
No numerical performance improvement or completed EMU acceptance is claimed.
