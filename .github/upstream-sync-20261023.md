# EVU-preserving upstream synchronization

Integration branch: `sync/upstream-20261023` in XSAI_external_vector and NEMU.
The existing feature branches and all pre-sync source snapshots remain available.
No push is performed. `origin` remains the qihangGuo fork; official `upstream/master`
is the synchronization source. Child repositories follow the parent-pinned version,
merged with existing local commits instead of replacing local EVU/CDC code.

Pinned upstream commits:
- XSAI: `c1c8c65101903d931604be937e98f5ba55cc2ef3`.
- NEMU: `16f993840b97afb491a12fc43ce50eb58f49e101`.

Compatibility changes preserve EVU dispatch/CSR/flags/VRF and both EVU and official
hash reference APIs. New Difftest ordering fields have legacy-safe defaults.
EVU AB writes require a handshake; stalled commands leave loader writes intact.
The standalone CUTE wrapper forwards vector result metadata. Matrix-prefetch plus
an independent accelerator clock is rejected until its notification CDC exists;
ordinary EVU/CUTE configurations retain their clock crossing paths. Matrix HostTLB
prototypes keep the matrix register identity as char, and the EVU reference
configuration follows the new upstream ISA switches.

Source compilation and native reference regressions pass. Softmax output maximum
relative error remains 1.64186611e-6; maximum |sum(p)-1| remains 6.59646116e-8.
The prior RTL trace still matches NEMU for all tested flag histories. This does
not establish Difftest success for newly generated RTL: the server must regenerate
Difftest interfaces/RTL, rebuild EMU and run the guest workloads. Do not reuse old
generated Difftest headers because ArchEvent/StoreEvent and EVU ABI have changed.
No local EMU, Verilog or guest binary was generated; nexus-am is outside scope.

Backups, original indexes, patches, Git bundles, logs and manifests:
`/tmp/xsai-evu-upstream-sync-20261023-232704`.
Validation details: `difftest/.github/upstream-sync-progress.md`.

Delivery is complete in the original working directories. Both main repositories
and CUTE/Difftest/XSAICache/Utility use the integration branch, with clean source
status. Original feature branches are unchanged; original uncommitted work is
also retained in Git stashes and the backup archives. The exact pre-normalization
index is archived for the one intent-to-add entry. Source compilation and all
native regressions pass again in the original directories. nexus-am and the other
XSAI worktree retain their original HEAD and Git status.
