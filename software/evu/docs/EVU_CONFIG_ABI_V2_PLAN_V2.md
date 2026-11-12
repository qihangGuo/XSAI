# EVU configuration ABI 2 plan, revision 2

Date: 2026-11-09. Supersedes EVU_CONFIG_ABI_V2_PLAN.md after the user explicitly
chose rebuilding all binaries instead of retaining legacy compatibility.

## Scope and design

EVU-specific CPU decode, scalar RF routing, CSR state, NEMU, difftest and software
are in scope. HBL2, CUTE and unrelated instructions are unchanged. Preserve the
previous FMA-context/diagnostic implementation. Do not start local Java/Mill,
generate RTL, run emu or build guest/native binaries.

- Remove EQDESC addresses/storage and all eight checkpoint fields. Difftest
  carries 13 words with EVCFG last (index 12). NEMU and emu must be rebuilt
  together. EVCAP reports ABI 2, zero descriptors, returned-VL bit 27.
- Redefine custom0 funct7=0x45 as xv.setvli: x[rs1] is unsigned AVL, rd is
  actual VL, VTYPE[7:0] is instr[14:12] concatenated with instr[24:20].
- Redefine funct7=0x46, funct3=7 as xv.setvl: x[rs1] is AVL, x[rs2] is full
  VTYPE, rd is actual VL. Other funct3 values are illegal. No legacy aux format.
- Both use RVV VTYPE's low-byte field layout and one shared normalization rule.
  High/unsupported type bits set VILL and VL=0. AVL=0 is empty, UINT64_MAX
  requests VLMAX, rs1=x0 means zero, rd=x0 discards only the returned value.
  RVV's special rd/rs1-zero AVL rules are intentionally not part of this custom ISA.
- EVCFG reads remain permitted under EVU privilege checks. Writes are M-mode
  only for full context restoration. Normal software uses the new instructions.
- Reuse scalar RF read/write ports only when EVU is enabled. State updates at
  scalar retirement; keep noSpec/blockBack and final completion/status ordering.
  NEMU returns VL at scalar execution, never again at external completion.

## Phases and tests

1. Shared config rules and CSR removal. Server: `mill -i evuCheck.test.compile`
   and the added config-rule suite; core CSR tests use the core test module in
   build.sc. Check VL clamping, unsupported type, VILL, M-only writes and removed
   descriptor addresses. PASS requires all assertions and tests passing.
2. CPU read/writeback routing and decoding. Server:
   `make emu CONFIG=DefaultExternalVectorMatrixConfig ACCELERATOR_CDC_ENABLE=0 EMU_THREADS=8 EMU_TRACE=fst -j16`.
   Check no elaboration error or combinational loop, and EVU-disabled topology.
3. NEMU/software. Server NEMU: `make git_commit= riscv64-xs-evu-ref_defconfig`,
   `make git_commit= clean`, `make git_commit= -j8`; rebuild EVU guest tests with
   the existing toolchain. Run native reference and guest CSR/config regressions
   including rd=x0, rd=rs1/rs2, unsigned AVL, masks, permissions and context restore.
4. Integration: run NEW softmax and FlashAttention binaries with the matching
   emu/NEMU. Require GOOD TRAP, no mismatch, pending=0 and independent numerical
   tests. The changed software config sequence must be disclosed in performance
   comparisons; cycle changes cannot be attributed solely to the FMA context.

Local checks are source/static and artifact-free checks only. Hardware and
numerical execution results remain pending server work; no Linux run is
authorized locally. Record actual checks and outstanding work in the progress log.
