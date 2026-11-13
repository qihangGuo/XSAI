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
