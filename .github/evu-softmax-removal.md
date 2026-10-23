# Retired EVU Instructions and Softmax Removal

## Reserved Encodings

The following custom0 funct7 values remain reserved and must be rejected;
neighboring operations have not been renumbered.

| funct7 | Removed operation |
| --- | --- |
| `0x08` | Standalone CONVERT |
| `0x0a` | Standalone BIASADD |
| `0x38` | Dedicated Softmax |
| `0x49` / `0x4a` / `0x4b` | GELU / SiLU / RSQRT |

Saturn's internal FP conversion units and conversions required by
dequantization remain. Ordinary SEW32 ADD can perform bias addition with an
explicit source register. There is no replacement Softmax routine in this
change; software must stop issuing the retired encoding.

## Softmax Removal Scope

The dedicated Softmax path was removed on 2026-10-20:

- Delete `EvuOnlineSoftmax` and `EvuSoftmaxHbl2`, their scratch storage,
  command/completion routing, dedicated memory path and busy handling.
- Remove scalar decode, virtual-memory preflight and the NEMU numerical
  implementation for Softmax. `0x38` is illegal instead of being reused.
- Delete dedicated Softmax suites, the software intrinsic/example, fixture
  and fixture generator.
- Keep the Exp2 instruction, quantization, Saturn execution/VRF, CUTE matrix
  postprocessing and LOAD/STORE virtual-memory checks. Exp2 subsequently moved
  into the Saturn FP issue path as `VFExp2FU`; its old `EvuExp2` wrapper was removed.

Sources 0 through N-2 belong to Saturn, and source N-1 belongs to CUTE output.
With the default N=8, Saturn has seven source IDs. These IDs must not be
confused with internal client-owner numbers used to route memory responses.

## Retained Checks and Verification Limits

`EvuDispatchAdapterSpec` checks retired encodings and unsupported widths.
`EvuRetiredOpcodeSpec` checks that `0x38` completes with an exception and no
HBL2 request at SEW=8/16/32. The native MMU harness also checks illegal-opcode
rejection without memory/VRF effects.

After removal, main/test Scala compilation, focused component tests and
Saturn+Difftest CHIRRTL elaboration passed. The native MMU harness passed with
nine physical stores, remapping, precise faults, retry and guard checking.
The revised guest MMU source compiled to a RISC-V object. These are previous
results, not a new validation run performed during documentation cleanup.

```sh
PATH="$PWD/src/main/resources:$PATH" \
  mill -i evuCheck.test.runMain evu.EvuHbl2ElaborationCheck architecture
```

Three obsolete non-Saturn integration cases were subsequently removed: two
from `EvuTopSpec` and one from `EvuHbl2TileLinkSpec`. They expected a legacy
internal VRF or idle matrix-input handshakes inconsistent with the current
implementation. All three also failed against saved pre-removal sources.

The shared harness and structural check remain as `EvuTopHarness.scala` and
`EvuHbl2ElaborationCheck.scala`. Component tests and the server-only Saturn
virtual-memory integration test remain. Runtime coverage of the removed
32-byte TileLink multi-beat case has not been fully replaced.

The separate native matrix harness still has its pre-existing failure;
details and acceptance commands are in
[matrix server validation](evu-launch-server.md). Full-core EMU+Difftest
and the server-only Saturn simulation are not established by these checks.
