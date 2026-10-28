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

EVU completions publish status to the independent EVU CSR bank. They do not
update scalar FCSR, vxsat, FS/VS or core vstart. VRF/configuration changes and
FP flags dirty EVU ES; mstatus/sstatus.XS reflects that state. See
[CSR architecture](../../../.github/evu-csr-architecture.md).

Saturn store-data VAT release is an internal scheduling event. Architectural
Store completion now comes from the VMU after the final ordered write Ack,
preventing completion while only 352/512 bytes have been observed.
`EvuSaturnMemoryTagSpec` covers all three element widths, reordered responses,
tag reuse, delayed/error final responses and completion backpressure.

Implemented computation includes integer arithmetic/logic/shifts/saturation,
widening, reductions, slides/gather, FP arithmetic/reductions, FP16/FP32 Exp2,
and packed FP32 probability quantization to CUTE. Standalone DEQUANT/REQUANT/QUANT
and fixed-point QuantToCute/SETCFG are retired: both RTL and NEMU reject
funct7=0x09/0x0b/0x4f/0x4c/0x4e. Special operations
have no VRF writeback interface; FP quantization preserves all EVU registers, while
CUTE matrix-stream dequantization retains its separate arithmetic and HBL2
output path. Opcode coverage alone does not establish equivalence for every
configuration. SETVL/SETVLI update the EVU configuration bank without scalar
rd writeback in both RTL and NEMU; arithmetic SEW follows configured EVU VTYPE.

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

The fixed-point QuantToCute and SETCFG implementation has been removed. The
old descriptor CSR addresses/checkpoint words remain reserved context slots,
without an execution consumer. The supported direct quantization operation is
FP32 probability packing (0x50). It has no mask-register predication; its
active lane mask is derived from VL. The accepted-write observer validates all
bytes and completion requires every expected write.

The compact pipeline update retains separate FP32 roundings and uses small
operand/output queues and current-chunk scale caches; it adds no VRF ports or
extra outstanding HBL2 matrix write. See [QUANT_PIPELINE.md](QUANT_PIPELINE.md)
for the memory budget, cache invalidation and actual throughput limits.


## Complete EVU Flash Attention and packed FP quantization

The immediate gather revision adds custom0 funct7=0x51 with an unsigned
five-bit rs1 index and EVCAP bit 26. `XV_BROADCAST` uses index zero and avoids
the elementwise vector-index gather path. The corresponding model changes
are in `NEMU/src/isa/riscv64/instr/evu/{evu-internal.h,model.c,compute.c}`;
the reference regression adds byte/halfword/word, VL=0/tail, alias and
out-of-range-index cases. The server's `NEMU_evu` shared library must be
rebuilt along with the EMU and guest. Local checking of this model revision
is syntax-only; it has not been executed against a rebuilt shared library.
The RTL revision passed full-core Scala compilation and 12 focused Treadle
tests for immediate gather, operand collection, dispatch and synchronous VRF
behavior. These checks do not replace server difftest or cycle measurements.

The funct7=0x50 FP32-to-packed-INT8 command replaces the retired fixed-point
interface. It supports direct CUTE AB
register and row selection, fixed RNE multiply/add, clamp and RTZ conversion.
`evcap[25]` advertises it for DLEN=256. It needs no additional CSR address.

NEMU checks each accepted CUTE write against its independent SoftFloat results;
it does not import DUT quantized values into its oracle. Reference AB bytes
persist across launches so subsequent PV uses independently computed P. FP
completion requires every write. The native regression checks 34 FP writes,
wrong/missing/duplicate events and flags, source VRF
preservation, and full P-before-launch/PV. Full guest kernel semantic checks
pass for two Q128/KV256/D64 cases against independent FP64 goldens.

See [Flash Attention](FLASH_ATTENTION.md) for accuracy limits, complete data
path, matched performance methodology and server rebuild/run commands. These
new changes have Scala compile, memory-only elaboration and native semantic
coverage; full server RTL/difftest and performance validation remain pending.
