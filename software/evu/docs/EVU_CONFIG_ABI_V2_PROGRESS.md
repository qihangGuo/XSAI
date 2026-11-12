# EVU configuration ABI 2 progress

Date: 2026-11-09.

Active plan: EVU_CONFIG_ABI_V2_PLAN_V2.md. The user explicitly rejected keeping
old binaries compatible. Revision 1's fresh opcodes, legacy aliases and 21-word
snapshot padding will not be implemented. Use redefined 0x45/0x46, 13 snapshot
words and M-mode-only EVCFG restoration writes instead.

| Phase | Source | Local checks | Server execution |
| --- | --- | --- | --- |
| CSR cleanup / normalization | Complete | Source checks pass | Pending |
| Decode / scalar result | Complete | Encoding checks pass | Pending |
| NEMU / software | Complete | C syntax-only passes | Pending |
| Integration | Tests/docs added | Python regressions pass | Pending |

Read-only audit confirmed EQDESC has no remaining execution consumer, but CSR
readback, software save/restore, capability bits and the 21-word difftest payload
still reference it. The current MRELEASE unit has one integer source and no
integer result port, so enabling decode xWen alone is insufficient. Legacy
configuration encodings overload rd and rs1-number bits; fresh encodings are
required to preserve old binaries while providing ordinary rd/rs1/rs2 roles.

That last compatibility proposal was superseded by the user's instruction:
0x45/0x46 are now redefined directly, and all bins/libraries must be rebuilt.

Implemented CSR ABI 2, 13-word snapshots, explicit context-buffer version,
M-mode EVCFG restoration, immediate/register returned VL, and common config
normalization. Updated scalar FU read/write routing only with EVU enabled.
Read source shares IntRD(1,2), avoiding STD1's existing IntRD(3,2); writeback
shares IntWB(3,2), avoiding the uncertain-latency priority constraint on port 4.
No prior instruction's priority or physical RF port count was changed.

NEMU now publishes rd before queued completion; tests intentionally overwrite
rd before completion to catch erroneous delayed writes. Reference/guest tests
and docs were migrated off descriptors and old setvl encodings.

GCC/Clang syntax-only checks passed for changed model/guest sources and native
tests. LLVM text encoding agrees with the new bit layout. clang-18's null-stream
assembler crashed; llvm-mc textual output succeeded. No object/binary was
generated. Scala tests have not been compiled/run, no emu or Linux run occurred.
Server compile, disabled-EVU elaboration, privilege/redirect tests, numerical
regressions and performance validation remain required; see EVU_CONFIG_ABI_V2.md.

The server build exposed an elaboration error in IssueQueue.scala:839 after
SETVL gained its second scalar input. MSET0 and MRELEASE/EVU share a queue with
two source-status entries, but MSET0's dequeue has only one register-cache
index. The old bulk connection assigned both entries to that single index.
The connection now selects each EXU's own source count, with an elaboration
requirement that all destination indices have a source. Equal-width queues
retain the same connections; no register-file port or scheduling rule changes.
Source review and git diff --check passed. Local RTL generation remains
disabled at the user's request; the corrected build needs server validation.
