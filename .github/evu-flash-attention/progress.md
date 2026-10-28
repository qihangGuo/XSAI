> Follow-up: the compact pipeline task retires fixed-point 0x4c/0x4e and replaces
> the serial implementation. Earlier legacy validation below is historical;
> see [current progress](../evu-quant-pipeline/progress.md).

# EVU Flash Attention progress

| Phase | Status | Local verification | Server EMU |
| --- | --- | --- | --- |
| FP32 quantization/direct CUTE oracle | local work complete | Scala compile, 3 in-memory elaboration tests, 34 FP + 2 legacy checked native writes: PASS | pending |
| Complete Flash Attention guest | implemented | RISC-V syntax-only and 2 full native Q128/KV256/D64 cases: PASS | pending |
| Documentation/final review | local work complete | legacy computation and 3-launch matrix regressions: PASS | pending |

Existing add/online-softmax/matrix server passes exercise the previous
implementation, not the new FP32 quantization command. Existing working-tree
changes are preserved. No EMU, Verilog or guest binary is generated locally.

## Phase 1: FP32 quantization and independent CUTE oracle

### Changes made

- Added custom0 funct7=0x50 (FP32 source vs2, inverse scale rd, scalar target
  `(row<<2)|AB-reg`), separate RNE multiply/add, clamp and RTZ conversion.
- Added packed CUTE bank/entry writes, active-lane flags, target/group/length
  validation and evcap bit 25. Legacy fixed-point opcode 0x4c remains distinct.
- Added difftest event at the accepted physical CUTE write port; NEMU checks
  target/mask/data against its own SoftFloat calculation before applying the
  reference bytes. New FP command completion requires all writes to be checked.
- Made reference AB register bytes persist across matrix launches, with validity
  tracking, so directly quantized P can be produced before the PV launch.

### Test results

- `mill -i xiangshan.test.compile`: PASS, including CUTE and difftest Scala.
- `mill -i evuCheck.test.testOnly evu.EvuFpQuantElaborationSpec`: PASS (3 tests,
  VLEN 512/4096 and a small legacy configuration, memory-only CHIRRTL).
- `EvuFpQuantSpec` behavioral suite compiles; execution is server-only.
- Native FP quantization regression: PASS (34 checked writes; VL 0/1/7/8/9/
  31/32/33/63/64, IEEE special values/flags, bad target/mask/data, duplicate and
  missing writes, preserved VRF, P-before-launch and downstream PV).
- Existing native computation/softmax/CSR regression: PASS (including 896
  FP16/FP32 scalar flag history cases and seven 512-element online-softmax cases).
- Existing matrix regression: PASS (3 launches, 6144 result beats, 6144 RMW stores,
  invalid metadata/data/flags/duplicates/premature fences all rejected).
- Negative tests deliberately print mismatch diagnostics; their final PASS and
  zero exit status distinguish expected rejection from a test failure.

### Issues and debugging

- Initial native PV operands were reversed. The CUTE oracle orders output as
  `[first source row][second source row]`; QK uses Q,K and PV must use P,V-transpose.
  Fixed native regression and guest operand order; downstream PV now passes.
- The new direct-write observer initially used the entire physical VRF byte
  count for legacy quantization. RTL emits only `VL << SEW` bytes, so a partial
  final entry would be checked against a full mask. Fixed the extent and added
  a 33-byte regression: two events pass, full final mask and duplicates fail.
- VLEN512 has a one-bit chunk index. Padding before taking its low two bits
  avoids an out-of-range slice in packed output positioning.
- A normal NEMU build hit read-only ccache outside the sandbox. Re-running with
  approved elevated permissions succeeded. NEMU uses `git_commit=` throughout.

## Phase 2: complete guest and independent accuracy checks

### Changes made

- Added the complete tiled QK / online softmax / direct P quantization / PV /
  normalization kernel, all vector arithmetic on EVU. CUTE dequantized results
  use the existing HBL2 path. Added capability check, warm-up and three timed runs.
- Added independent FP64 unquantized and dynamically P-quantized goldens, per-
  element quantization bounds, strict quantized-error and normalized-MSE checks.
- Added a native semantic adapter for the exact guest kernel, using NEMU
  instructions in synchronous mode. It does not model RTL timing/backpressure.

### Test results

- RISC-V clang `-fsyntax-only -Wall -Wextra -Werror`: PASS; no guest binary.
- Two full Q128/KV256/D64 native cases: PASS, 27356 EVU/matrix instructions.
- Baseline inputs: maximum absolute error 4.290e-6, normalized MSE 8.214117e-6.
- Stronger second-tile K (late maximum/rescaling): maximum absolute error
  4.171e-6, normalized MSE 9.393455e-6.
- For both cases, the quantized-golden maximum absolute error is below 1e-9 and
  normalized MSE below 1e-12 (integer-scaled output rounds down to zero).

### Issues and debugging

- Row broadcast buffers and scalar V layout copies have a cost; timing includes
  these operations. Historical RVV 322302 cycles is a reference only, not a new
  matched measurement or evidence of speedup.

## Phase 3: final review

### Changes made

Added `software/evu/docs/FLASH_ATTENTION.md` with ABI, numerical checks,
actual data flow, timing boundaries and server build/run commands. Updated NEMU
reference status and native regression documentation. The legacy partial-entry
compatibility check passes. FP capability is conditional on DLEN=256; hardware
preflight and physical CUTE geometry enforce the same constraint.

### Test results

- Final `mill -i xiangshan.test.compile`: PASS after geometry/preflight changes.
- Final RISC-V syntax-only and all three repositories' `git diff --check`: PASS.
- Latest rebuilt NEMU: legacy compute, FP+legacy CUTE-write, matrix and two full
  Attention native regressions all PASS. No local RTL/EMU/guest bin was built.
- Difftest C++ integration with newly generated headers remains server-only;
  Scala difftest compilation and API/field review pass locally.
- All task edits remain under XSAI_external_vector (including difftest) and
  NEMU. No nexus-am edit, commit or push was performed by this task.

Full server RTL/difftest and performance results remain pending. Rebuild EMU,
NEMU reference and guest together; old binaries do not implement funct7=0x50.
