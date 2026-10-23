# EVU Virtual Memory Integration

## Contract

Scalar `xv.load` and `xv.store` accept process virtual addresses. Before
scalar writeback/retirement, a range checker uses an additional XSAI DTLB
requestor, the shared PTW and PMP/PMA checks. It checks every touched 64-byte
cache line and records each 4 KiB physical page separately. STORE also checks
read permission because partial-line stores use read/modify/write. Device
memory and non-default PBMT are rejected.

The core drains older stores before preflight and blocks younger dispatch
until EVU completion and the status pipeline drain. Interrupts are deferred
while EVU is busy. This serializes memory commands rather than preserving
scalar/EVU memory overlap. Remote page reclamation still requires normal TLB
shootdown acknowledgement.

Successful translations travel with the retired command, including through
the accelerator clock crossing. EVU splits requests into aligned lines before
looking up their physical pages. Canonical virtual addresses remain 64 bits
outside Saturn; its memory engine generates relative addresses and preserves
carries across page boundaries. Each command builds a fresh page map, while
the core DTLB receives existing SFENCE/CSR invalidations.

Preflight faults use the normal core exception path, including the failing
virtual address in xtval and guest-physical information when supplied by the
TLB. Faults precede EVU register/data-memory writes. Software can repair the
mapping, execute SFENCE.VMA and retry the entire instruction. Translation's
page-table access/dirty-bit updates are not rolled back. Bus errors after
preflight retain asynchronous completion semantics without rollback.

Preflight uses the accepted EVU VL/vtype, including VL=0. The checked extent
is limited to one VLEN-byte register; larger LMUL memory spans are rejected.
Strided/indexed virtual-memory operations are not exposed by the current
scalar command interface.

`xv.launch` has no bounded complete CUTE output span for pretranslation. It
requires M-mode, Bare SATP, virtualization disabled and effective data
privilege M. Its CUTE stream uses privileged physical addresses outside the
LOAD/STORE page map. Partial-line RMW also requires exclusive software
ownership against other CPU/CUTE writers; it is not atomic.

## Reference and Difftest

NEMU preflights LOAD/STORE through its MMU and permission checks before
queuing effects. It captures physical addresses and publishes LOAD register
state only on completion. RTL store events describe acknowledged physical
64-byte PutFullData transactions; the reference checks data, preserved guards,
observed-byte coverage and ordered completion. Rebuild both RTL/EMU and NEMU
when changing these event semantics.

The reference subset includes LOAD/STORE, FP32 matrix launch and WAIT/FENCE.
It has an independent EVU register bank, but does not implement the full EVU
ISA: SETVL/SETVLI and ordinary arithmetic/configuration commands are not
covered. See [matrix validation](evu-launch-server.md) for its narrower launch
producer contract and known native matrix-test failure.

## Shared PTW Integration

The additional EVU DTLB port has its own optional one-port `PTWNewFilter`
group. The prefetch group remains exactly two ports. The EVU group
participates in shared arbitration, refill routing, guest-address metadata,
memory-index return and existing SFENCE/CSR invalidations. EVU-disabled
configurations retain the original three groups.

`EvuPtwFilterSpec` checks both configurations, simultaneous requests,
backpressure, shared-page refills, fault metadata, invalidation and retry.
This core-level check is needed because standalone EVU compilation does not
exercise PTW port connections.

## Focused Checks and Recorded Results

From the XSAI repository root:

```sh
mill -i evuCheck.test.testOnly evu.EvuAddressCheckSpec \
  evu.EvuMemoryConfigSpec evu.EvuSaturnAddressSpec \
  evu.EvuDispatchAdapterSpec evu.EvuHbl2LineAdapterSpec
mill -i evuMmuCheck.test.testOnly xiangshan.evucheck.EvuPtwFilterSpec
mill -i xiangshan.test.compile
```

Earlier checks passed focused component tests and main/test Scala compilation.
They cover line-by-line permissions, noncontiguous pages, range bounds,
accepted VL/vtype, zero VL, canonical addresses, page-boundary carry, masks,
backpressure, RMW preservation and PTW routing. They do not establish a
full-core RTL/EMU/Difftest pass.

After Softmax removal, the native MMU harness passed nine physical stores,
cross-page VRF transfer, remapping, precise faults, retry, preserved guards
and retired `0x38` rejection. The revised guest source compiled to a RISC-V
object. The linked guest and full Saturn simulation remain server checks;
the server-only test was canceled when its enable flag was unset locally.
These are recorded results, not new runs during documentation cleanup.

## Server Verification

Use current matching XSAI/NEMU sources and the existing server LLVM/Abstract
Machine environment (`AM_HOME`). Commands below run in one shell; adjust
`NEMU_REPO` for the actual reference checkout.

```sh
export XSAI_REPO=/nfs/home/guoqihang/workspace/XSAI_external_vector
export NEMU_REPO=/nfs/home/guoqihang/workspace/NEMU_evu
export NEMU_HOME="$NEMU_REPO"

cd "$XSAI_REPO"
mkdir -p out/ccache
PATH="$PWD/src/main/resources:$PATH" \
  EVU_SERVER_RTL_TEST=1 CCACHE_DIR="$PWD/out/ccache" \
  mill -i evuCheck.test.testOnly evu.EvuVirtualMemorySpec

cd "$NEMU_REPO"
make git_commit= riscv64-xs-evu-ref_defconfig
make git_commit= -j8

cd "$XSAI_REPO"
make -C software/evu git_commit= ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=virtual_memory_evu_test
make git_commit= emu -j16 EMU_THREADS=8 CONFIG=DefaultExternalVectorMatrixConfig
./build/emu -C 20000000 \
  -i software/evu/build/virtual_memory_evu_test-riscv64-xs.bin \
  --diff "$NEMU_REPO/build/riscv64-nemu-interpreter-so"
```

The guest maps consecutive Sv39 virtual pages onto noncontiguous physical
pages and checks LOAD/STORE data and guards. It injects a missing second page
and a read-only second page, checks causes 13/15 and mtval=0x40001000, verifies
unchanged VRF/data, repairs the mappings and retries. Acceptance requires
`faults=2 failures=0`, GOOD TRAP, and no Difftest mismatch, assertion or
simulation limit. The guest enables mnstatus.NMIE and keeps its trap handler
from using compiler-generated vector temporaries.

The Saturn integration test also checks source IDs in 0 through N-2 and
illegal `0x38` rejection without a memory request. Its default 64-byte TL port
does not replace the removed 32-byte multi-beat runtime test.

### Native MMU Reference Harness

This runs without RTL, using the freshly configured NEMU headers/library:

```sh
cd "$XSAI_REPO"
mkdir -p out
cc -std=gnu11 -O2 -D__ISA__=riscv64 \
  -I "$NEMU_REPO/include" -I "$NEMU_REPO/src/isa/riscv64/include" \
  -I "$NEMU_REPO/src/isa/riscv64/instr" \
  software/evu/host/evu_mmu_ref_test.c -o out/evu-mmu-ref-test -ldl
./out/evu-mmu-ref-test "$NEMU_REPO/build/riscv64-nemu-interpreter-so"
```

Acceptance requires exit 0 and the final `EVU MMU` success summary. One
physical-store/guard mismatch is deliberately injected and must be rejected;
that diagnostic alone is not a failure if the harness reaches its success
summary. The retired `0x38` instruction must trap without memory/VRF effects.

Old incremental transfer lists/checksums describe earlier source snapshots.
Use current source synchronization, including deleted files, as described in
[the documentation index](README.md).
