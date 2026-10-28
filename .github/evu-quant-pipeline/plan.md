# Compact EVU quantization pipeline

Scope: XSAI_external_vector and NEMU only. Preserve the Flash Attention implementation and existing fixes. No local EMU execution, Verilog generation, or guest binary build; no commit/push.

1. Replace serial FP probability quantization with three elastic arithmetic stages and two operand slots. Keep separate RNE multiply/add, clamp/RTZ, flags and accepted-write completion semantics.
2. Replace generic fixed-point/FP postprocessing with dedicated two-stage matrix dequantization. Cache only the current eight A/B scales, invalidate on accepted VRF writes, retain two output slots and ordered metadata. Extend the small hazard scoreboard to cover pipeline occupancy.
3. Retire fixed-point QuantToCute (0x4c) and SETCFG (0x4e) in RTL and NEMU. Keep descriptor CSR addresses/storage for context ABI compatibility, with no execution consumer. Remove fixed-point arithmetic and legacy direct-write layout.
4. Update meaningful pipeline/backpressure/flush/cache/retired-opcode tests and documentation. Compile behavioral tests, perform memory-only elaboration, rebuild NEMU and run native reference regressions. Server RTL validation and measured speedup remain separate.

Budget: roughly 0.5 KiB of datapath storage, including retained input/output queues; metadata and control accounted for separately. No new VRF SRAM ports, full-vector scale cache, HBL2 merge buffer or additional outstanding matrix writer.
