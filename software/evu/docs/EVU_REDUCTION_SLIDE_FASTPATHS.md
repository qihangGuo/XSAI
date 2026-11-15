# FP32 min/max tree and aligned slide

These are backend optimizations of existing RVV operations. There are no new
XV encodings, capability bits, numerical approximations or NEMU changes.
`DefaultExternalVectorMatrixConfig` enables both through the default
`EvuParams.fp32MinMaxTree` and `EvuParams.alignedSlide` settings. Either setting
can be disabled independently when constructing `EvuParams` for hardware A/B
or synthesis. Keep the guest image and all other configuration settings fixed.

## Min/max reduction

FP32 `fredmin`/`fredmax` retain the existing lane-wise streaming accumulators
and multi-context return routing. Long vectors still stream all input groups
and merge the accumulator slots actually used. The final horizontal fold is
one pipelined-tree operation instead of repeated issue/feedback rounds.
At DLEN=512, the tree has 15 pair comparisons over four registered levels;
one 16-element reduction now issues one streaming operation and one tree fold,
instead of one streaming operation and four ordinary folds. This is an issue
count, not a promise of two cycles per reduction.

The tree shares the comparison FU's result channel with ordinary two-cycle
operations. The sequencer, per-context pipe scheduler, shared result-slot
arbiter, VRF reservation and completion/exception VAT tagging use the actual
latency. At DLEN=512 the tree latency is four cycles; smaller datapaths are
parameterized with a minimum latency of two. Existing VRF write masks preserve
all destination bytes except element zero. FP16 reductions, sums and ordinary
element-wise comparisons retain their original execution paths.

FP32 min/max streaming, merging and tree folding agree on signed-zero ties,
quiet/signaling NaNs and active-element exception flags. Unused accumulator
lanes use quiet NaN as the min/max identity, so they cannot turn an all-NaN
reduction into infinity. The architectural initial value remains in lane zero;
masked or tail elements cannot raise exceptions through unused comparisons.
The same FP32 reduction semantics apply with the tree disabled.
An all-masked FP32 min/max copies the initial value without canonicalizing a
NaN or raising NV; it skips unused slot merges and uses one final copy.

## Aligned slide

For standard integer `slideup`/`slidedown` VX/VI forms, the bypass requires an
unmasked instruction, vstart=0, nonzero VL, supported SEW, and whole-DLEN-group
alignment of offset, VL and VLMAX. Slide-up additionally requires offset<VL;
slide-down requires offset<VLMAX. This conservative first version falls back
for partial tails, masked/restarted forms, oversized offsets and slide1.

Eligible commands do not enter the special permutation issue queue or feed
`GatherUnit`/`Compactor`. The execution sequencer reads the source group at
destination_index-offset for slide-up, or destination_index+offset for
slide-down, through its existing VRF port. Slide-up starts at the first written
group and preserves the destination prefix. Slide-down inserts zero for groups
beyond VLMAX without reading outside the source register group. In-place legal
slide-down walks forward, reading each needed source before overwriting it.

The execute issue queue advertises the direct source-read dependency. Normal
sequencer RAW/WAR/WAW checks, operand retention, VRF grants, result reservations,
completion and backpressure remain in force. Source hazards are conservatively
retained until instruction issue finishes. This adds no VRF port or FP4-specific
packing buffer. Alignment uses DLEN/8 bytes, not a fixed 64-byte constant.

## Validation and server use

### Operand and single-group slide follow-up

Checkpoint `142923fc9` preserves the previous RTL/software before this follow-up.
Three independent `EvuParams` switches default to true:

- `singleGroupSlide`: unmasked VX/VI slideup/slidedown, vstart=0, whose source
  and destination accesses fit within the first DLEN group. Slide-up requires
  offset<VL and VL<=group elements; slide-down additionally requires VL+offset
  to fit both the group and VLMAX. Existing VRF ports supply the data to a
  parameterized shifter; the original write masks preserve untouched bytes.
  This bypass avoids the special permutation queue and compactor. All other
  cases retain the original path, including cross-group, masked, restarted,
  oversized-offset and slide1 operations. No new instruction or FP4 buffer is
  introduced.
- `extendedResultForwarding`: connects integer/permute, FP divide, ordinary
  conversion, XX8 and MXFP paths to the existing full-EG bypass. Iterative
  results may forward while held under writeback backpressure, but completion
  and exception flags still follow the original write-acceptance handshake.
  Only full masks qualify; packed FP4/FP8 and reduction partial writes do not
  forward. VAT age and intervening-writer checks are retained.
- `crossInstructionPrefetch`: checks each ordinary issue-queue head against
  older writers and hints up to three first-group operands into the existing
  collectors while the current instruction is at its tail. Normal demand
  reads retain priority. Reductions and instructions requiring the generic
  gather/slide machinery do not use this lookahead. Scalar-index broadcasts
  hint the addressed source group, with out-of-VLMAX indices excluded.
  This is operand lookahead, not arbitrary out-of-order issue
  or cache prefetch. Setting it false retains the previous EXP2-only hint.

### Collector invalidation fix (2026-11-15)

The format-11 RMSNorm run exposed an operand-coherence bug at its first
`amax / 6` division (`pc=0x80001e20`, instruction `0x697faa0b`). E0 arithmetic
could leave a full-group speculative read in the divide operand collector:
the E0 mirror served the demand without consuming that read, and subsequent
VRF writes did not invalidate it. Reassigning the quantization constant to
v31 then reused its earlier `[512, 0, ..., 0]` group despite the physical VRF
already containing sixteen sixes. This produces DZ rather than reference NX.
The production VRF/collector Treadle reproducer observed the stale group;
the same sequence without prefetch returned the updated broadcast.

`RegisterReadCollector` now cancels a matching read when the E0 mirror serves
the operand, suppresses same-cycle hints for that bypassed EG, and invalidates
cached data on matching physical VRF writes.
Invalidation includes masked pipeline writes and staged load/iterative writes,
independent of diagnostic counters or VAT ownership; zero-mask writes leave
data valid. If an invalidated read is still outstanding, its untagged response
must drain before a replacement request is accepted. Unrelated EG writes and
normal successor prefetch retain their existing behavior. No ISA, NEMU,
quantization formula, software schedule or fence changes are needed.

Focused checks use Treadle, without generating Verilog or guest binaries:

```sh
PATH="$PWD/src/main/resources:$PATH" mill -i evuCheck.test.testOnly evu.EvuRmsnormPrefetchSpec
PATH="$PWD/src/main/resources:$PATH" mill -i evuCheck.test.testOnly \
  evu.EvuSynchronousVrfSpec evu.EvuReadPrioritySpec \
  evu.EvuScalarResultForwardingSpec evu.EvuWriteCompletionSpec
PATH="$PWD/src/main/resources:$PATH" mill -i evuCheck.test.testOnly \
  evu.EvuSkewedVrfSpec -- -z 'pipeline successor'
PATH="$PWD/src/main/resources:$PATH" mill -i evuCheck.test.testOnly \
  evu.EvuSaturnOperandSpec -- -z 'prefetch real'
```

Local Treadle checks passed the rewritten v31 reproducer, staged partial LL
writes, E0 cancellation with a simultaneous hint, unrelated-EG retention,
zero-mask/ownerless physical-write notifications, and invalidation while the
old response is buffered, returning, or still pending. Normal real-VRF
successor prefetch and backpressure checks also passed. The production
`EvuAreaTopSpec` CHIRRTL structural check passed. A full all-EG bank traversal
was stopped because of interpreter runtime and is not counted as passed.

The subsequent November 15 server run passes all 11 format-11 cases and EVU
Difftest with `pending=0`: quantization takes 3659 cycles and the row-major
complete operator takes 5101. See the
[checkpoint record](checkpoints/2026-11-15-rmsnorm-5101.md) for the full results,
artifact-provenance limits and remaining regression/synthesis work. This is
joint RTL/software workload acceptance, not an isolated hardware speedup.

### Follow-up acceptance

No divider/FMA replication or reduction-context increase is included. The
new bypass muxes, group shifter and hint logic still require timing/area checks.
`EvuGroupSlideSpec` covers byte/halfword/word shifts, partial write masks,
source capture, RAW/WAR/WAW stalls, backpressure and fallback selection at
DLEN=128/256/512. `EvuIterForwardingSpec` covers held full divide results,
partial-result rejection and completion timing. `EvuIndependentOperandSpec`
also covers all three tail hint ports versus normal next-group prefetch.
The full-backend `EvuSlideSpec` adds short-group, partial-tail, in-place
slide-down and cross-group fallback transitions for each SEW/LMUL combination.

The follow-up is statically compiled locally; these RTL simulations must run
on the server, not be inferred from compilation success:

```sh
mill -i evuCheck.test.testOnly evu.EvuGroupSlideSpec evu.EvuAlignedSlideSpec \
  evu.EvuIterForwardingSpec evu.EvuVectorResultForwardingSpec \
  evu.EvuIndependentOperandSpec evu.EvuSlideSpec
```

For an attributable performance comparison, first save a checked checkpoint
run. Build the new EMU once and compare baseline versus optimized software on
that same EMU. The software switches can also be varied independently:

```sh
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=rmsnorm_nvfp4_evu_test \
  EVU_RMS_LANE_SUM=0 EVU_RMS_QUANT_RENAME=0 EVU_RMS_QUANT_BANKED=0 \
  BINARY="$PWD/software/evu/build/rmsnorm_baseline-riscv64-xs"
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=rmsnorm_nvfp4_evu_test
```

The baseline uses the previous arithmetic schedule, and the default image
prints `format=11 lane_sum=1 quant_rename=1 quant_banked=1` among its configuration fields.
Use `EVU_RMS_QUANT_BANKED=0` with the other switches at 1 to reproduce the
format-10 timed arithmetic, or use `compare_rmsnorm_software.sh` to compare
the new bank-aware assignment and instruction order on one unchanged EMU.
Run each image with the same NEMU and EMU arguments, then compare the checked
logs with `compare_rmsnorm_runs.py`. Do not mix a reverted format-9 experiment
with the format-8 checkpoint baseline. Check `sumsq`, `rms_evu`, `quant_evu`,
both full fusion schedules and checked Softmax; a lower isolated stage is not
enough to accept an end-to-end regression.

For timed rather than whole-program counters, build `EVU_RMS_VERBOSE=1`, run
EMU with `-- +evu_mem_trace=1`, and parse using
`summarize_rmsnorm_counters.py`. Counters can overlap and must not be added as
independent lost cycles. New speedups and RTL functional PASS remain unclaimed
until server acceptance.

### Previous tree/aligned-slide validation

`EvuMinMaxTreeSpec` checks consecutive inputs and bubbles at DLEN=64/128/256/512,
random bit patterns, NaNs, infinities, signed zeros, subnormals, mixed two/four
cycle outputs and exception VATs. Production-sequencer tests cover min/max
seeds, tails, masks, long vectors, two concurrent contexts and source stalls,
with and without the tree. `EvuAlignedSlideSpec` checks direct VRF addressing,
preserved dependency checks, source capture, writeback/issue backpressure,
out-of-range zero groups and fallback classification at DLEN=128/256/512.
The existing full-backend `EvuSlideSpec` also covers aligned up/down, LMUL,
in-place down, fallback transitions and completion backpressure.

Focused server commands:

```sh
mill -i evuCheck.test.testOnly evu.EvuMinMaxTreeSpec evu.EvuAlignedSlideSpec
mill -i evuCheck.test.testOnly evu.EvuInterleavedReductionSpec -- -z 'short FP32'
mill -i evuCheck.test.testOnly evu.EvuSlideSpec
set -o pipefail
make emu CONFIG=DefaultExternalVectorMatrixConfig ACCELERATOR_CDC_ENABLE=0 \
  EMU_THREADS=8 EMU_TRACE=fst -j16 2>&1 | tee xsai_build.log
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=rmsnorm_nvfp4_evu_test
./build/emu --diff ../NEMU_evu/build/riscv64-nemu-interpreter-so \
  -C 100000000 -i software/evu/build/rmsnorm_nvfp4_evu_test-riscv64-xs.bin \
  2>&1 | tee rmsnorm_hw_opt.log
python3 software/evu/scripts/compare_rmsnorm_runs.py rmsnorm.log rmsnorm_hw_opt.log
```

Keep the completed format-8 software run as the hardware baseline, not format 7,
to avoid attributing software changes to RTL. Also rerun existing math,
low-precision, vector-add, CSR and checked Softmax regressions before acceptance.
Area, frequency and speedup remain unmeasured until server RTL/synthesis runs.

Local checks compile/typecheck the EVU sources and Scala test sources against
cached dependencies. Treadle checks passed the four standalone tree widths,
the mixed-latency comparison/result-tag case and the three direct-slide widths.
The expanded production-context seed/mask regressions and full-backend slide
regression remain server acceptance requirements. No local EMU or guest image
was built, and no generated SoC Verilog or measured speedup is claimed.
