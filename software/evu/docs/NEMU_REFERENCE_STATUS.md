# EVU Reference Status

The current NEMU reference has a separate 32 x 4096-bit EVU register bank and
EVU-local VL, VTYPE, VSTART, VLENB and configured state. These are independent
of the scalar core's RVV bank. Custom-0 instructions are decoded into EVU jobs;
pending results forward to younger jobs, while architectural updates wait for
the DUT's ordered completion events. The checker validates physical stores,
completion flags, register snapshots and local vector control state.

`tools/evu-ref-test/evu_compute_ref_test.c` in NEMU mirrors the pending
LOAD/ADD/STORE/FENCE sequence in `vector_add_evu_test.c`, including e8/e16/e32,
three rounds, aliases v1/v17/v31, four outputs and guard bytes. This native host
test passes with the rebuilt reference. It does not replace the full EMU run
on the server; the RISC-V guest has not been compiled or run locally. The user
has since confirmed that `vector_add_evu_test.c` passes on the server. This
result covers the ADD workload, not the FP or matrix paths.

EVU completions carry a VS-dirty indication through the ordered AMU status
path to the scalar CSR block. Successful VRF writes and SETVLI/SETVL dirty
the shared mstatus/sstatus.VS field; STORE, WAIT/FENCE, descriptor setup and
QuantToCute do not. Zero-length memory/quantization leaves VS unchanged;
ordinary empty ALU operations conservatively dirty VS, matching NEMU.
Exceptions suppress the dirty indication. The AMU pulse bypasses the normal
ROB status register so the CSR update aligns with the completion event's
three-cycle Difftest delay. Rebuild EMU to include this connection; an older
binary can abort on its first LOAD with reference VS=Dirty and DUT VS=Initial.
The focused completion-status regression passes with the production AMU and
MstatusModule, including completion backpressure, faults, redirects and the
NewCSR input-stage delay. The subsequent server ADD pass confirms the
exercised LOAD/ADD/STORE completion path.

Saturn store-data VAT release is an internal scheduling event. Architectural
Store completion now comes from the VMU after the final ordered write Ack,
preventing completion while only 352/512 bytes have been observed.
`EvuSaturnMemoryTagSpec` covers all three element widths, reordered responses,
tag reuse, delayed/error final responses and completion backpressure.

Implemented computation includes integer arithmetic/logic/shifts/saturation,
widening, reductions, slides/gather, FP arithmetic/reductions, FP16/FP32 Exp2,
and descriptor-based fixed-point QuantToCute. Standalone DEQUANT/REQUANT/QUANT
are retired: both RTL and NEMU reject funct7=0x09/0x0b/0x4f. Special operations
have no VRF writeback interface; QuantToCute preserves all EVU registers, while
CUTE matrix-stream dequantization retains its separate arithmetic and HBL2
output path. Opcode coverage alone does not establish
equivalence for every configuration. In particular, these differences remain
outside this quantization-path removal:

- After SETVLI/SETVL, `EvuTop.scala` replaces incoming SEW with configured SEW.
  NEMU `instr/evu/model.c:evu_execute` continues to use instruction funct3 as
  `job->sew`. An e16 configuration followed by funct3=e8 arithmetic therefore
  selects different element widths. The standalone ADD test does not configure
  VTYPE, so this difference does not affect it.
- NEMU `model.c:execute_setv` schedules a scalar destination write when rd is
  nonzero. `backend/decode/EvuDecoder.scala` declares all custom EVU instructions
  with `xWen=false`; rd also participates in the packed VTYPE configuration.
  These instructions need one agreed scalar writeback contract.
EVU FReduceSum maps to Saturn's `fredusum`. The reference now matches the
four-slot unordered tree with FP16/FP32 rounding on every add, replacing the
serial reference responsible for the softmax log's 1-ULP mismatch. The
segmented FMA's five exception flags and byte positions, and the active-lane
mask for reduction folds, are also corrected. The focused softmax regression
compares all intermediate FP32 destination words and flags, verifies stores,
and feeds the RTL trace through `difftest_evu_complete`/`difftest_evu_store`.
The subsequent server log exposed another NEMU issue: per-command exceptions
were extracted as the difference from preexisting scalar FCSR flags, losing NX
when scalar code had already set NX. Collection now uses temporarily cleared
flags, restores scalar state after evaluation, and publishes the full command
flags at completion. A native reproduction of `0x645f228b`, 896 scalar-history
cases, and three replays of the saved RTL trace with prior flags 0x00/0x01/0x1f
pass. This follow-up did not run EMU or generate RTL or a RISC-V guest.
This does not substitute for the full-core EMU rerun; see
[softmax analysis](../../../.github/evu-softmax-difftest.md).

SETCFG descriptors now contain only scale/bias register indices, zero point,
shift, output clamp width and validity. The current scalar `EvuFuncUnit`
supplies aux=0 for SETCFG and QuantToCute, as does NEMU, so software cannot yet
select nonzero shifts or descriptor indices other than zero. RTL SETCFG also
does not declare a scalar source for its zero point. These encoding limitations
remain; the retained instruction is not a complete software quantization API.
RTL still does not check descriptor validity, while NEMU does. NEMU records
QuantToCute results but does not check or apply CUTE A/B writes; the native
regression verifies VRF preservation and saturation, not that downstream effect.

v0 predication remains a separate integration task. This change only limits
QuantToCute byte enables and saturation flags to the VL byte extent; it does not
enable masked quantization. Quantization retains its existing 32-bit word
layout, with command SEW defining the active byte extent, rather than adding
packed e8/e16 output conversion. CUTE destination matrixReg/bank remain fixed
at zero; this removal does not add layout conversion or destination selection.

Validation after removing VRF quantization writeback: `evuCheck.test.compile`
and `xiangshan.test.compile` pass. Seven focused RTL suites (14 tests) pass,
covering retired encodings, CUTE backpressure/flush, VL byte enables, unchanged
VRF contents, saturation and matrix-stream dequantization. The rebuilt NEMU
native computation regression also passes. Rebuild both EMU and the NEMU
reference on the server; existing ADD, Exp2 and matrix-dequantization guest
sources do not use the retired instructions and require no changes for this
removal. No guest C workload or full EMU was compiled or run locally.
