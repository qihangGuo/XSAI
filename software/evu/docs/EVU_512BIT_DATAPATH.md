# Pure-Vector 512-bit Datapath

| Resource | Current configuration |
| --- | --- |
| VLEN | 4096 bits per architectural register |
| DLEN / MLEN | 512 / 512 bits |
| FP32 element-group width | 16 elements |
| Architectural VRF | 32 registers, 16 KiB total |
| Physical SRAM | 8 lanes x 4 banks, each 64x64 bits with byte masks |
| SRAM ports | One read or write per bank per cycle |
| Ordinary FMA contexts | 1, sharing one existing FMA datapath |
| Reduction contexts | 2 |
| Ordinary memory command window | 4 |
| External physical memory tags | 8 |

Quantization, matrix dequantization, CUTE direct writes, the matrix-result
bridge and dedicated matrix stores are removed. Their three private VRF
requesters are removed from the ordinary read arbiter. Ordinary operand
collectors, reduction input/tail logic, result forwarding, EXP2, VMU line
coalescing, tag ownership and final physical acknowledgements are retained.

Width is not a throughput measurement: command supply, dependencies, VRF
arbitration and memory service still determine useful element issue rates.
Use the existing formal FENCE interval counters for comparisons. This migration
has no new measured cycle count or synthesized area result.

The area boundary exports only dispatch, completion/drain, physical memory and
optional performance signals. The retired `--with-bridge` and `--tile-dim`
options are no longer accepted. `EvuAreaTopSpec` checks the production SRAM
geometry and absence of all dedicated matrix/quantization modules.

See [EVU_PURE_VECTOR_PLAN.md](EVU_PURE_VECTOR_PLAN.md) for server verification.
The original widening experiment and its historical measurements are archived
in [EVU_512BIT_DATAPATH_PRE_PURE_VECTOR.md](archive/matrix-vector/EVU_512BIT_DATAPATH_PRE_PURE_VECTOR.md).
