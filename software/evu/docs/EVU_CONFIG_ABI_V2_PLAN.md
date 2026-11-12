# EVU configuration ABI 2 plan

SUPERSEDED before implementation by EVU_CONFIG_ABI_V2_PLAN_V2.md. No legacy
compatibility, new opcodes or padded snapshot described below is shipped.

Date: 2026-11-09. This round is explicitly authorized to change EVU-specific
CPU decode, scalar read/writeback routing, CSR state, NEMU and software. HBL2,
CUTE, unrelated CPU instructions and the prior FMA-context work stay intact.
Local validation must not generate RTL, run emu, build guest/native binaries
or start a Java/Mill build. Hardware and reference execution remain server work.

## Design

- Remove EQDESC storage and addresses 0x8d0..0x8d7. Access becomes illegal.
  EVCAP reports ABI 2, zero descriptors and a returned-VL capability bit 27.
  Preserve the 21-word difftest layout with eight reserved zero words; do not
  silently move EVCFG from word 20. Old descriptor-bearing contexts are not
  compatible. New software context buffers carry an explicit magic/version.
- Allocate new custom0 encodings: funct3=7, funct7=0x60..0x67 for immediate
  VTYPE (instr[27:20]), and funct7=0x68 for register VTYPE (x[rs2]). x[rs1]
  supplies unsigned AVL, rd receives actual VL. AVL=0 remains empty, x0 is
  ordinary zero, UINT64_MAX requests VLMAX, rd=x0 discards only the result.
  VTYPE uses the RVV low-byte layout. Unsupported/high bits set VILL and VL=0.
  These custom instructions do not implement RVV's special rd/rs1 zero rules.
- Keep legacy 0x45/0x46 semantics and writable EVCFG for complete context
  restoration and ABI-1 software compatibility. Normal APIs use new instructions.
  Share one normalization helper between scalar result calculation and CSR
  publication. Do not publish authoritative state before scalar retirement.
- Reuse integer read/write ports for the EVU-containing execution unit only
  when EVU is enabled; no new physical RF port. Keep configuration noSpec and
  blockBack protection through completion/status publication.
- NEMU writes returned VL at scalar execution, including async mode. External
  completion must not write rd again. rd aliases must read their inputs first.

## Phases and validation

1. CSR cleanup and shared normalization. Check version, removed addresses,
   zero snapshot holes, VILL, LMUL, VL clamping and restoration. Server:
   `mill -i evuCoreCheck.test.compile` (confirm module name in build.sc), then
   run the added config/CSR suites. PASS means all assertions and tests pass.
2. Decode, scalar return and ordering. Check two scalar operands, existing WB
   arbitration, permission faults, redirects, rd=x0 and rd=rs1/rs2. Server:
   `make emu CONFIG=DefaultExternalVectorMatrixConfig ACCELERATOR_CDC_ENABLE=0 EMU_THREADS=8 EMU_TRACE=fst -j16`.
   PASS requires no elaboration errors or combinational loops.
3. Reference/software migration. Server NEMU: `make git_commit= riscv64-xs-evu-ref_defconfig`,
   `make git_commit= clean`, `make git_commit= -j8`. Run native reference tests
   and the guest CSR/config tests using the matching rebuilt library.
4. End-to-end: run old softmax bin first to isolate prior scheduling changes,
   then newly rebuilt softmax/FlashAttention/config bins to test ABI 2.
   Require GOOD TRAP, no mismatch, pending=0, and separate numerical checks.

Final correctness and performance claims require server results. No long Linux
simulation is authorized locally; context, privilege and redirect regression
coverage must be recorded as pending rather than claimed passed.
