# Pure-Vector Reference Status

NEMU owns a separate 32x4096-bit EVU register bank and CSR ABI 2. Pending
commands forward reference results to younger commands; architectural results,
FP flags and saturation are published in the DUT's ordered completion stream.
The independent CPU RVV register bank and scalar floating-point state remain
separate. The CSR snapshot contains 13 words.

Supported computation retains integer e8/e16/e32 operations, ordinary FP16/FP32
arithmetic, EXP2, reductions, permutations, E0/scalar-source forms and memory.
SETVLI/SETVL return actual VL in scalar rd. EVU uses its existing four-slot
unordered FP reduction model and independent command exception collection.

Launch (0x18), direct quantization (0x50/0x5e), their matrix oracle and AME hooks
have been removed. Their encodings trap even at VL=0. EVCAP bit 25 reads zero;
other ABI 2 fields and instruction numbers are unchanged. Ordinary AME/CUTE
execution is still modeled independently.

Difftest retains ordinary EVU store/completion/drain/VRF/CSR checking. Physical
stores are checked against independent expected bytes, including guard bytes
and merged owners. Final store completion requires every expected byte to have
been observed. A bus fault requires an independent reference fault injection;
DUT failures are never accepted as reference answers. Drain requires no pending
commands and an empty payload, because command results have already published
their flags and faults.

The ordinary EVU extension is applied to official difftest `0fdb354a`. It does
not request matrix-stream or direct-CUTE-write symbols. Unsupported FAST/FORK
checking is rejected. Accelerator replay requires preserved EVU state and, when
matrix replay is used, the matching AME snapshot exports.

For independent CUTE compatibility, the matrix completion hash implementation
is ported from official NEMU `6f7be63b`: CRC32C, including unwritten bytes and
empty regions, with the unchanged result structure (`lo`, `hi=0`, `bytes`).
This is separate from removal of the fused EVU matrix oracle.

No rebuilt reference or simulator has been executed locally for this migration.
The exact server commands and acceptance criteria are in
[EVU_PURE_VECTOR_PLAN.md](EVU_PURE_VECTOR_PLAN.md); actual checks are recorded in
[EVU_PURE_VECTOR_PROGRESS.md](EVU_PURE_VECTOR_PROGRESS.md).
The previous reference report is archived under
[matrix-vector/](archive/matrix-vector/NEMU_REFERENCE_STATUS_PRE_PURE_VECTOR.md).
