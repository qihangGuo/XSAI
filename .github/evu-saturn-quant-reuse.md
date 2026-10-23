# Reusing Saturn for register-based quantization

Status: historical, unimplemented hardware-sharing proposal. Register-output
DEQUANT/REQUANT/QUANT have since been retired (0x09/0x0b/0x4f are illegal).
Only QuantToCute and CUTE matrix-stream dequantization retain dedicated
arithmetic, and neither writes the VRF. SETCFG retains fixed-point parameters
for QuantToCute; its old floating-mode descriptor fields have been removed.
The register-output operations and migration steps below describe the earlier
proposal, not the current ISA. The expansion controller,
temporary-result routing and execution arbitration described below are not
part of the current datapath. The standalone EVU CONVERT encoding 0x08 and
BIASADD encoding 0x0a are reserved; Saturn's internal FPConv unit and the
conversions needed inside floating dequantization remain available. See
[retired instructions](evu-softmax-removal.md).

## Implementation before register-output quantization was removed

EvuVectorBackend routes ordinary operations to EvuVectorSubsystem (Saturn),
and quantization commands to EvuSpecialSequencer. The latter instantiates one
EvuQuant for register commands and an EvuMatrixDequant with its own EvuQuant
for CUTE matrix-stream processing. These are separate hardware instances.
Saturn already contains integer ALUs, widening multiply, shift/clip units,
FPConv and FP FMA. Multiple opcodes share each EvuQuant instance; there is no
separate complete arithmetic unit per opcode.

The selected Split issue structure includes FPConvFactory through fpMisc.
FPConvPipe has three pipeline stages and the EVU supports FP16/FP32 rather
than FP64. This says nothing by itself about the
latency of a whole vector or a composite instruction under contention. Ordinary
CPU RVV instructions still target the core RVV bank, not the EVU register bank.

## Arithmetic mapping

The following are operation sequences, not currently callable assembly macros.
T0/T1 are internal temporary values, not assumed free architectural registers.

| Operation | Sequence using existing functional units |
| --- | --- |
| Integer bias addition (ordinary ADD) | ADD32(input, explicitly selected bias vector) |
| Floating DEQUANT | T0 = signed INT32-to-FP32(input), then FP32 FMA(T0, scale, bias) |
| Fixed-point DEQUANT | T0 = SUB32(input, zero), T1 = signed widening MUL32x32(T0, scale), ADD64(T1, sign-extended bias), arithmetic right shift, retain low 32 bits |
| QUANT / REQUANT | Fixed-point multiply/add/shift sequence, compare and clamp to selected bounds, ADD(zero), then apply the specified destination representation |
| QUANTTOCUTE | Same arithmetic sequence, followed by an explicit result transfer to the CUTE write bridge |

The EvuQuant implementation also has optional modes: scale can first
be multiplied by log2(e), and a scale-product mode multiplies the intermediate
result by a broadcast parameter instead of adding an ordinary bias. These
require extra FP multiply operations in the same order; they cannot silently
be collapsed if bitwise compatibility is required. FMA must remain fused.

## Integrating the sequence while preserving quantization opcodes

1. Keep the existing externally visible DEQUANT/QUANT/REQUANT opcodes.
   Decode each command and its descriptor into a parent job with fixed source
   registers, destination, VL, masks, rounding and output-format parameters.
2. Bias addition already uses the ordinary EVU ADD route to Saturn; software
   explicitly selects its bias source register and SEW32. There is no remaining
   BIASADD opcode or implicit descriptor lookup for this ordinary ADD.
3. For composite operations, expand each DLEN group into the operations above.
   Reuse Saturn's instantiated functional units through request arbitration;
   a small expansion controller issues a dependent operation only after its
   input is ready. Do not instantiate a second multiplier/FMA/converter.
4. Provide explicit storage and operand routing for intermediate results.
   Saturn's current instruction interface names architectural registers and
   does not automatically support private temporaries. A group-sized temporary
   buffer plus tagged operand/result bypass into the existing execution paths
   needs actual backend changes. For full 32x32-to-64 multiplication, retain
   both widened halves. A plan that merely invents temporary v0/v31 registers
   would corrupt software state and is not acceptable.
5. Carry parent job ID, epoch, group index and last-operation metadata alongside
   the internal work. Reserve sources/destination against ordinary and CUTE
   operations until the appropriate release point. Track internal completions
   separately: one quantization command produces one architectural completion,
   not one completion for each internal operation. Accumulate fflags/vxsat over
   active elements; scalar publication and Difftest must observe final results.
6. Commit destination groups using the existing VRF arbitration and tail/mask
   policy. For QUANTTOCUTE, transfer only a valid computed result and wait for
   the actual CUTE write handshake. Flush cancels tagged work and releases
   resources; restart/exception visibility must follow an explicit ISA policy.
7. Remove only the register-command EvuQuant datapath after equivalence and
   performance tests pass. Keep the CUTE stream path as a separate decision;
   reusing the same units there introduces bandwidth contention and may require
   additional buffering.

This changes the issue, operand and result paths, not just opcode translation.
It trades arithmetic replication for extra scheduling, buffers and contention.

## Semantics that must be settled first

- Current fixed-point arithmetic uses a 32-bit subtraction, a 64-bit product
  and addition, followed by arithmetic shifting. Ordinary narrow MUL/MAC would
  lose high bits before the shift. Preserve the defined wrap widths, or change
  them explicitly with new reference results rather than silently widening all
  arithmetic and claiming equivalence.
- Current fixed-point shifting does not use vxrm rounding. A rounded vnclip
  sequence is not an automatic bitwise replacement. Explicit shift/min/max
  can preserve the current result; a rounding change needs a specification.
- Current REQUANT clamps before adding zero. It emits one 32-bit slot per
  input element even for 8/16-bit clamp bounds; it does not pack INT8/INT16.
  Standard packed narrowing would change the memory/register layout.
- Current floating DEQUANT converts the signed integer input directly and
  does not subtract zero in that path. Define any correction to this behavior
  separately. Preserve each FP32 rounding point and define NaN/Inf handling.
- Current QUANT and REQUANT select the same arithmetic operation. A distinct
  FP32-to-INT8 QUANT meaning would be a new semantic contract, not a pure
  hardware-sharing refactor.
- Bias/scale broadcasting, descriptor validity, saturation after zero-point
  addition, mask/tail handling and flags from inactive elements need explicit
  tests. One arithmetic formula alone is not a complete instruction definition.

## Lower-complexity baseline and validation

An alternative baseline is a software routine using explicitly allocated EVU
temporary registers and multiple basic instructions. It avoids hidden hardware
temporaries and a composite completion protocol, but exposes extra instructions
and register traffic. The current custom EVU instruction mapping does not yet
expose every needed conversion/narrowing operation; those mappings would need
to be added before such a routine can execute. This document does not add them.

Before replacing the fused datapath, compare the existing/reference semantics
and proposed sequence on extreme signed inputs, saturation thresholds, every
shift, nonzero zero points, FP32 rounding and exceptional values, masks/tails,
overlapping source/destination registers and backpressure/flush. Add the chosen
quantization semantics to NEMU and Difftest; their current memory/matrix
subset does not validate these commands. Measure synthesized retained area,
clock timing and whole-workload cycles with CUTE contention. Source instance
counts alone do not establish an area saving because synthesis can remove
unused or constant logic.
