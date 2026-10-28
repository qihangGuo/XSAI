# EVU Flash Attention

`flash_attention_evu_test.c` implements the complete noncausal, single-head
Q=128 / KV=256 / D=64 workload used by
`xsai_flash_attention_ame_vfexp2_4row_vector_max_lmul8`. Inputs are INT8 Q/K/V;
online softmax and final output use FP32. P is dynamically quantized per query
row and 64-key chunk. The inputs and scales match the existing RVV baseline.

## Actual data path

For software phase timing, see [cycle breakdown](FLASH_ATTENTION_BREAKDOWN.md)
and the independent `flash_attention_evu_breakdown` target. The original
test's profiling hooks compile to no-ops unless that target enables them.
For quick cycle iteration without guest FP64 reference work, use
`flash_attention_evu_profile_cold`. For independent correctness plus one
warmed profile, use `flash_attention_evu_correct_warm`; it checks the warm-up
and measured result against the FP64 goldens. See the
[cycle breakdown guide](FLASH_ATTENTION_BREAKDOWN.md) for commands and
acceptance criteria.

1. CUTE computes a 128x128x64 QK tile. CUTE -> EVU applies Q/K/softmax/log2(e)
   scales and stores the dequantized score tile through HBL2.
2. EVU performs row maxima, online rescaling, Exp2, sum and per-chunk P scales.
3. New FP32 quantization writes packed INT8 P directly from EVU VRF into CUTE
   AB registers tr1 and tr2. There is no P buffer in memory and no core RVV
   quantization. Software waits for these writes before launching PV.
4. Software packs V-transpose; row scales are already compact and contiguous.
   CUTE computes each PV chunk, and EVU dequantizes through HBL2 into one of
   two result buffers. Software then loads both contributions and updates
   output in one pass per key tile, preserving the two rounded additions.
5. EVU computes one FP32 reciprocal of the running softmax denominator per
   query row and uses it as a scalar operand for the 64 output features. The
   final EVU fence waits for the last memory acknowledgement before timing ends.

The score buffer holds one 128x128 tile, not the full QxKV score matrix. Two
128x128 PV buffers pad unused output features 64..127 with zero. Row sums and
P scales each hold one FP32 value per row. The legacy row-max allocation is
retained, but the scalar path reads and writes only its first element.
The default `scalar_batched_v3` kernel processes four independent rows using
instruction-local scalar operations, with zero scalar-work VL switches.
`EVU_FLASH_SCALAR_OPS=0` retains the previous `compact_batched_v2` path and its
320 scalar-work VL switches. Explicit
synchronization and scalar V layout copies remain inside the measured kernel.
Matrix dequantization still uses HBL2 instead of a direct result-to-VRF path.
The latest server cold result for `scalar_batched_v3` is 323025 kernel cycles.
The same run is near the historical 322302-cycle RVV result, but its cold/warm
conditions are not matched, so it does not demonstrate a speedup. See the
breakdown guide for phase data and the next correctness/warm-cycle run.

The new default needs the matching RTL/emu, NEMU reference and guest rebuilt
on the server. It adds FP32/m1 E0 memory/arithmetic operations, vector-scalar
operands and scalar-scale quantization (funct7 0x52..0x5e). Global VL is unchanged
and inactive register elements are preserved. See
[the implementation and validation scope](FLASH_ATTENTION_OPTIMIZATION_ROADMAP.md)
for configuration switches, 1RW VRF constraints and remaining validation.

## Added instruction

| Field | Meaning |
| --- | --- |
| opcode / funct7 / funct3 | custom0 (0x0b) / 0x50 / 2 |
| rs1 | scalar target `(query_row << 2) | AB_register` |
| rs2 | EVU FP32 probability source register |
| rd | EVU FP32 inverse-scale source register; no VRF writeback |
| constraints | one physical VRF group, FP32, VL<=64, DLEN=256 |

For each active lane: RNE multiply by the inverse scale, separately RNE add
0.5, minNum with 127, clamp below to zero, unsigned RTZ conversion. The packed
output is in [0,127]. IEEE flags accumulate only across active lanes; this FP
clamp does not set integer `evxsat`. The operation uses fixed RNE, independently
of scalar FRM. It neither changes scalar FCSR nor writes the EVU source VRF.

For default CUTE (eight banks, 32-byte entries, 128 rows and K=64):
`bank = row % 8`, `entry = (row / 8) * 2 + element / 32`.
Each eight-FP32 beat writes eight bytes with byte enables inside one entry.
The old fixed-point `QuantToCute` (0x4c) and `SETCFG` (0x4e) are retired and
raise illegal instruction. The FP command uses a three-stage elastic pipeline
with two operand slots; matrix dequantization uses two arithmetic stages and
current-chunk scale caches. See [compact pipelines](QUANT_PIPELINE.md) for
storage, cache coherence and throughput limits.

`evcap[25]` advertises this command for DLEN=256. The guest checks this bit and
VLENB=512 before running. Its normal independent EVU CSR configuration is
`evcfg.Configured=1`, SEW=32, LMUL=1, with VL=64 for vector work and VL=128 for
matrix scale loading. Only the legacy scalar0 build changes VL to 1 for scalar
ratios/reciprocals. It uses the
existing `mevstate` enable/permission bits; no additional CSR address was
needed for this instruction.

## Earlier Single-Lane Division Optimization

The passing server run before this optimization measured a 2816182-cycle
median. Its separate breakdown measured 1716464 cycles in `softmax_quantize`
and 304309 in `final_normalize`, together 71.74% of the profiled kernel.
The iterative FP divider previously evaluated 64 identical quantization
ratios per row/chunk and 64 output divisions per row.

Before instruction-local scalar support, both executables used these sequences;
the new default keeps the same division counts without the VL switches:

| Operation | Previous FP32 divisions per kernel | Current divisions | Sequence |
| --- | ---: | ---: | --- |
| P inverse scale | 32768 | 512 | VL=1: `127/max_exp`; VL=64: gather lane 0 into all lanes |
| Final normalization | 8192 | 128 | VL=1: `1/row_sum`; VL=64: broadcast and multiply output |

In the current batched revision v12..v15 hold scalar inverses and their
lane-zero broadcasts, v27 holds zero/reduction seeds, v28 holds 127 and v30
holds 1. Vector inputs remain in their registers across VL changes. The per-chunk tiny-denominator
guard and the FP32 ratio rounding are preserved. Reciprocal multiplication
can round differently from direct division, so both FP64 goldens and all
existing error limits remain in force.

The EVCFG writes can drain older commands. Both VL switches, the broadcast
and the extra normalization multiply remain inside the original phase and
end-to-end timers. There are no new explicit fences. The division-only change
kept the workload, row-state layout, reference calculation, warm-up and repeat
counts; the later compact/batched revision changes row-state layout and shares
the VL switches across four rows as described above.
The startup banner identifies this version as
`division=single_lane_scale_reciprocal_broadcast broadcast=gather_imm scale_divisions=512 normalize_divisions=128`;
the counts refer to one full kernel, excluding reference calculation.
Actual optimized cycles require a new server run; the old measurements
above do not establish the improvement.

Local GCC/Clang RISC-V syntax checks and the native adapter syntax check pass.
A focused instruction-sequence check using the existing synchronous NEMU
shared library also passes: nine ratio inputs match the original 64-lane
division bit for bit, with identical flags and preserved source/tail state;
640 normalization values differ from direct division by at most one ULP
(maximum absolute change 1.49011612e-8). These are targeted checks, not a full
execution of the updated C kernel or proof of its FP64 acceptance. No local
guest binary, EMU or Verilog was built or run for this optimization.

## Immediate broadcast and operand return bypass

This RTL revision adds custom0 funct7=0x51, `XV_GATHER_IMM(vd, index,
vs2, sew)`. The rs1 field is an unsigned five-bit immediate index; no scalar
or vector rs1 register is read. It maps to Saturn's `RGATHER_VI` path, which
reads the selected source element group once, caches the element and writes
one DLEN group per micro-op. `XV_BROADCAST(vd, vs2, sew)` uses index zero.
Indices outside the effective VLMAX produce zero. Active elements are copied
bit for bit, with no FP operations or changes to FP rounding.

The five broadcast sites in the pre-fusion Flash Attention kernel use this
path. One full kernel has 1920 broadcasts, also retained by the row-fused
revision below. At FP32/VL64/DLEN256, their execute
micro-ops fall from 122880 to 15360, excluding stalls and other instructions.
This is a work-count reduction, not a measured kernel speedup. The v29 zero
register is still required for output initialization and reduction seeds.

The execute sequencer also forwards a returning synchronous VRF operand
directly to issue when the other operands and execution path are ready.
When issue is stalled it captures the response as before. Capture state is
cleared on every issued group and on same-cycle instruction replacement.
The physical VRF remains four 64-bit lanes with eight 1RW banks per lane;
its ports, bank mapping, arbitration and write priority do not change.

The RTL and NEMU advertise immediate gather in EVCAP bit 26. The Flash
Attention programs require this bit so an older emulator fails at startup
with a capability message. Rebuild the server EMU, NEMU reference shared
library and guest together. The local NEMU sources are under `../NEMU`;
copy these changes into the server's `NEMU_evu` checkout used by `--diff`.
The native semantic adapter also emits the new instruction.

The focused RTL regression uses Treadle and real synchronous VRF banks; it
does not generate Verilog. It covers byte/halfword/word data, unsigned indices
0/7/16/31, partial VL, masks, source/destination aliases and issue stalls.
The NEMU regression source adds matching unmasked cases plus VL=0 and an
out-of-range index with fractional LMUL. New server end-to-end cycles and
complete numerical validation are still required for this RTL revision.

Local verification passed `mill -i xiangshan.compile` and all 12 tests across
`EvuGatherImmSpec`, `EvuSaturnOperandSpec`, `EvuDispatchAdapterSpec` and
`EvuSynchronousVrfSpec`. The FP32/VL64 physical-VRF broadcast test observes
one source read and eight micro-ops issued on consecutive cycles. Operand
tests also cover same-bank conflicts, backpressure, older writes and
capture clearing across element groups and simultaneous dispatch. NEMU and
guest changes passed syntax-only checks; this revision was not run with a
rebuilt NEMU shared library or a complete EMU. No local guest/native binary
build or Verilog generation was performed.

## Row-fused software kernel

The `kernel=row_fused_v1 output_init=first_pv normalize=last_pv` revision
changes the shared kernel used by the normal, breakdown, warmed profile and
cold profile executables; the cold wrapper remains a single run without
FP64 reference work. It adds no instruction, RTL change or NEMU semantic
change and uses the same immediate-gather revision described above.

For each row, both 64-key score chunks remain in v1/v2 while the kernel
updates max/alpha, rescales the old output and computes/quantizes both
chunks. The current max, alpha and intermediate denominator stay in EVU
registers. Only the completed denominator is stored. The update is still
`(old_sum * alpha + chunk0_sum) + chunk1_sum`, and each chunk retains the
original independent sum/max reduction and FP32 scale division. No
probability reduction is combined across rows or chunks. The alpha array
and its dead first-tile initialization are removed.

The first PV contribution initializes output without clearing or loading
the previous output. An EVU addition with +0 is deliberately retained to
preserve the original signed-zero and exception behavior. The last PV
accumulation computes the reciprocal and normalization before its output
store, preserving the FP32 add-then-multiply sequence and avoiding an
intermediate store/reload. The final PV phase still ends after the memory
acknowledgements. Constants, scale ratios, reciprocal divisions, CUTE tile
shape, inputs, goldens and error thresholds do not change.

For the default shape, source-level accounting removes 2432 VL64 vector
memory commands (608 KiB of logical loads/stores) and the 32 KiB alpha
array. It does not claim an equal reduction in physical bus traffic or
kernel cycles. This count includes score reuse, row-max reuse, alpha
elimination, intermediate denominator reuse and output-pass fusion. The
first PV's 128 additions with zero remain intentionally.

| Removed memory commands | Count | Logical bytes |
| --- | ---: | ---: |
| Reload scores for softmax | 512 | 128 KiB |
| Reload row max for both chunks | 512 | 128 KiB |
| Store/load alpha, including unused first-tile stores | 384 | 96 KiB |
| Intermediate chunk0 denominator store and chunk1 reload | 512 | 128 KiB |
| Output zeroing, first old-output load, final intermediate store/reload | 512 | 128 KiB |

The native semantic regression now poisons output and row state before each
of its two input cases to catch stale-state reads. This regression source
is syntax-checked locally but must still be executed with the rebuilt
reference on the server.

Breakdown uses `profile_schema=row_fused_v1`: 23 intervals and nine phase
aggregates replace the old 28 intervals and ten aggregates. Compare
`row_softmax_quantize` with the old sum of `max_alpha_update` and
`softmax_quantize`, and combine output phases when comparing the fused
last accumulation/normalization. See [phase definitions](FLASH_ATTENTION_BREAKDOWN.md).
End-to-end performance and full numerical acceptance still require the
server run; local checks for this revision do not execute a guest or build
any guest/native binary, EMU or Verilog.

Local checks pass RISC-V `-fsyntax-only -Wall -Wextra -Werror` for all four
entrypoints, native-adapter syntax checking and preprocessing checks for
the one/two-run performance modes. A source-AST-driven symbolic dependency
check compares the new schedule with the pre-fusion register schedule for
all 128 rows, both key tiles, all 512 quantized row/chunks and 8192 final
outputs. It verifies the operand/rounding-operation order and register
preservation through VL=1, but does not evaluate FP arithmetic or establish
NEMU/RTL correctness. The phase labels and 23-interval accounting are also
checked structurally.

## Independent reference and accuracy

NEMU computes quantized bytes using SoftFloat. A new difftest CUTE-write event
checks register, bank, entry, byte mask and every active byte against this
calculation. It updates the reference AB state from the reference's own bytes
only after an event matches. AB state survives launch, so the PV oracle uses
independently calculated P. New FP-quant completion requires every write to
have been observed. EMU and NEMU must both be rebuilt; older binaries cannot
validate the new command/interface.

The guest checks two independent FP64 goldens outside the timed interval:

- Unquantized Attention, with a per-element bound derived from half a P
  quantization step plus 2e-6 FP allowance; normalized MSE <=2e-5.
- The same tiled, dynamically P-quantized algorithm, with absolute error
  <=`2e-6 + 1e-4*abs(quantized_golden)` and normalized MSE <=1e-8.

The FP64 exponential is a separate degree-16 polynomial after range reduction;
it does not reuse EVU scores, Exp2 results or quantized bytes. Normalized MSE
means sum of squared output errors divided by sum of squared FP64 outputs.
Logs scale errors by 1e9 and NMSE by 1e12, then truncate to integers. A printed
zero is therefore a bound, not a claim of bitwise FP64 equality.

Reference construction reuses results only after checking exact input equality:
identical Q rows share their three FP64 result rows, and V columns equal across
all KV keys share their output and error-bound calculations. All QxD outputs
are still populated and checked with the same tolerances. The default inputs
contain seven distinct Q rows and seven distinct V columns. This reduces FP64
exp calls from 65664 to 3591 and each reference's probability/value accumulation
from 2097152 to 12544 terms. These are operation counts, not measured cycle
speedups. Changed inputs are compared again on every call; distinct rows and
columns receive their own calculations, with no assumption of periodic input.
The EVU kernel, warm-up, repeats and timing boundaries are unchanged.

Before warm-up, the guest prints `reference begin`, followed by
`reference done cycles=... unique_queries=7/128 unique_value_columns=7/64`
for the default data. The reference cycle count excludes these progress prints
and is separate from the timed kernel's `e2e_cycles`.

Before the single-lane division optimization, local native checks passed for
the exact guest kernel on the synchronous NEMU model, including a second case
with stronger K in the second key tile to force late maxima/rescaling.
Maximum absolute errors against unquantized FP64 were
4.290e-6 and 4.171e-6; NMSE was 8.214117e-6 and 9.393455e-6. Both quantized-golden
maximum errors were below 1e-9. These are semantic checks, not RTL or timing
results. The native path does not test core retirement, cache ordering or CUTE
hardware backpressure.

## Server build and run

Run the following on the server, from `XSAI_external_vector`, with the server's
existing `AM_HOME` and LLVM toolchain. The immediate-broadcast and VRF-bypass
revision requires rebuilding both the EMU and NEMU reference. The earlier
division-only software revision could reuse the previous EMU/NEMU; this
no longer applies to a guest with `broadcast=gather_imm` in its banner.
Use the cold profile first for a quick comparison against the 1970504-cycle
cold result, then the warmed/full-validation modes for final reporting.
The hardware target is `DefaultExternalVectorMatrixConfig`, common matrix/EVU
clock (CDC disabled), VLEN=4096/DLEN=256 and the default CUTE geometry.

```sh
make -C ../NEMU git_commit= riscv64-xs-evu-ref_defconfig
make -C ../NEMU git_commit= -j16
mill -i evuCheck.test.testOnly evu.EvuFpQuantSpec
make emu -j16 EMU_THREADS=8 CONFIG=DefaultExternalVectorMatrixConfig
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=flash_attention_evu_test
./build/emu \
  --diff ../NEMU/build/riscv64-nemu-interpreter-so \
  -C 200000000 \
  -i software/evu/build/flash_attention_evu_test-riscv64-xs.bin \
  2>&1 | tee flash_attention_evu.log
```

Adjust the NEMU path if the server checkout is called `NEMU_evu`. `-C` is an
initial cycle budget, not a measured required count; initialization and FP64
validation also consume guest cycles. Retain the same memory/cache/clock and
simulation settings used for the RVV baseline. The behavioral suite and EMU
build above generate RTL and are server-only; no local RTL/EMU/guest-bin build
was performed for this task.

Expected acceptance: `HIT GOOD TRAP`, no difftest mismatch, each output check
has `failures=0`, EVU `pending=0`, and nonzero `checked_cute_writes`, matrix beats,
stores and completions. One warm-up plus three timed kernels nominally produce
16384 direct CUTE write events and 49152 matrix beats; these are derived counts,
not server measurements. Also rerun the existing add, online-softmax and matrix
guests after rebuilding the RTL/reference.

The test prints three `e2e_cycles/e2e_instret` samples and their cycle median.
Input generation, FP64 reference and output checks/printing are outside timing;
all V packing, scale packing, vector-state setup, matrix/vector work and fences
inside the kernel are included. The printed 322302-cycle RVV number is explicitly
historical. A defensible speedup requires rerunning both kernels with matching
input, build flags, timing boundaries, warm-up, clocks and cache configuration.
The passing pre-optimization EVU median was 2816182 cycles, slower than the
historical RVV result; no cycle result for the division optimization is
available yet.

## First-QK synchronization timeout

`flash_attention_evu.log` from the October 24, 2026 15:51:14 EMU reports
`reference done cycles=773191`, then 120000 cycles without a core commit.
The matching disassembly ends its commit trace at `0x8000102a` (`XV_FENCE`),
after the first warm-up QK launch at `0x80000ffe`. No matrix output beat was
checked; P quantization and the timed runs had not started. The final
`ABORT at pc=0x80000f42` points to the earlier output-zeroing loop, not the
last committed instruction.

The local `EvuSpecialSequencer` shared VRF port 1 between quant operand 1 and
matrix scale A. Interleaving their connections in one Scala loop let the
second quant default overwrite the first matrix request. The SRAM read
collector requires a valid matching request to acknowledge a response, so
matrix scale A never arrived and the matrix stream could not drain. Matrix
port selection now follows all quant defaults, with explicit valid/bits
selection and one ready assignment per matrix operand.

`EvuOperandCollectionSpec` covers the integrated special sequencer with both
scale-response orders, output backpressure, matrix/quantization/matrix
transitions and flush during scale collection. It uses Treadle, not Verilog
or EMU. Rebuild the server EMU from the corrected RTL source and rerun the
same guest; increasing the cycle limit does not repair this port connection.
Server RTL/difftest acceptance still requires the checks listed above.

## Quant-to-FENCE completion mismatch

The October 25, 2026 09:33:46 EMU reaches cycle 1663331 with 2048
matrix beats and 1024 CUTE writes checked, then reports a completion for
`0x80001100` (`XV_FPQUANT_TO_CUTE`) while NEMU expects `0x8000113a`
(`XV_FENCE`). The first warm-up QK result and the first probability chunk's
direct writes have passed comparison; PV and the timed runs have not started.

`AmuCtrlBuffer` marked the completed head `canDeq` but left `evuAccepted`
set until dequeue. The head pointer advances a cycle after the completion,
so checking only `evuAccepted` lets a back-to-back FENCE completion handshake
against the already-completed quantization entry. Difftest then labels that
FENCE completion with the old head's quantization PC/instruction, and the
actual FENCE entry loses its completion. This is a hardware handshake bug,
not a numerical tolerance issue or a reason to weaken reference checking.

Completion readiness now also requires `!head.canDeq`. The next completion
stays backpressured while the completed head dequeues, then handshakes
against its own entry. No guest algorithm, timing boundary or difftest
comparison rule changes. `EvuAmuCompletionSpec` exercises the production
AMU buffer with adjacent quant/FENCE completions, an intervening scalar
entry, distinct flags, an exceptional completion, and redirect suppression.
The focused local check uses the Treadle FIRRTL interpreter, not Verilog:

```sh
mill -i evuMmuCheck.test.testOnly xiangshan.evucheck.EvuAmuCompletionSpec
```

This focused check passed locally (1 test, 0 failures); Scala compilation
also passed. It does not establish full-system performance or server
difftest acceptance.

Rebuild the server EMU from the corrected hardware source and rerun the
same guest binary. Full RTL/difftest acceptance remains a server check.

## Local semantic checks (no guest or RTL)

After building the native NEMU shared reference from the parent code directory:

```sh
make -C NEMU git_commit= -j8
make -C NEMU/tools/evu-ref-test
NEMU/tools/evu-ref-test/evu_compute_ref_test NEMU/build/riscv64-nemu-interpreter-so
NEMU/tools/evu-ref-test/evu_fp_quant_ref_test NEMU/build/riscv64-nemu-interpreter-so
gcc -std=gnu11 -O2 -ffp-contract=off -fno-tree-vectorize \
  -D__ISA__=riscv64 -INEMU/include -INEMU/src/isa/riscv64/include \
  -INEMU/src/isa/riscv64/instr \
  XSAI_external_vector/software/evu/host/flash_attention_ref_test.c \
  -o /tmp/xsai-flash-attention-ref-test -ldl -lm
/tmp/xsai-flash-attention-ref-test NEMU/build/riscv64-nemu-interpreter-so
```

The quantization native test deliberately injects bad/missing/duplicate events
and flags, so rejection diagnostics are expected. Its final success line and
zero exit status are required. The native adapter shares the guest kernel C but
implements instruction execution through NEMU; it is not a substitute for the
server acceptance above.
