# Pure-Vector 512-bit Datapath

| Resource | Current configuration |
| --- | --- |
| VLEN | 4096 bits per architectural register |
| DLEN / MLEN | 512 / 512 bits |
| FP32 element-group width | 16 elements |
| Architectural VRF | 32 registers, 16 KiB total |
| Physical SRAM | 8 lanes x 4 banks, each 64x64 bits with byte masks |
| SRAM ports | One read or write per bank per cycle |
| Ordinary FMA contexts | 2, sharing one existing FMA datapath |
| Reduction contexts | 2 |
| Unified VAT/job command window | 32; no completion FIFO |
| Ordinary memory command window | 4 |
| External physical memory tags | 8 |

Quantization, matrix dequantization, CUTE direct writes, the matrix-result
bridge and dedicated matrix stores are removed. Their three private VRF
requesters are removed from the ordinary read arbiter. Ordinary operand
collectors, reduction input/tail logic, result forwarding, EXP2, VMU line
coalescing, tag ownership and final physical acknowledgements are retained.

Width is not a throughput measurement: command supply, dependencies, VRF
arbitration and memory service still determine useful element issue rates.
Use the existing formal FENCE interval counters for comparisons. The current
fused RMSNorm result is 3747 cycles; see
[EVU_CHECKPOINT_2026-11-19.md](EVU_CHECKPOINT_2026-11-19.md). There is no
synthesized area result for the unified-window change.

The area boundary exports only dispatch, completion/drain, physical memory and
optional performance signals. The retired `--with-bridge` and `--tile-dim`
options are no longer accepted. `EvuAreaTopSpec` checks the production SRAM
geometry and absence of all dedicated matrix/quantization modules.

See [EVU_PURE_VECTOR_PLAN.md](EVU_PURE_VECTOR_PLAN.md) for server verification.
The retired matrix-coupled widening experiment is available only in Git history.
