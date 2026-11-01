# L1/HBL2 diagnostic tests

EVU entry: `software/evu/vector_memory_path_evu_test.c`.
RVV entry: `nexus-am/tests/exp2test/tests/performance/vfexp2_memory_path_matched.c`.
The existing primary online softmax tests are not changed. The new entries
include their existing kernels, and share the driver in
`nexus-am/tests/exp2test/include/vector_memory_path_bench.h`. Synchronize the
header and the RVV Makefile as well as the EVU source when using the server.
No RTL, NEMU, CUTE, or new performance CSR is required.

## Questions answered

1. Does the unchanged 32x256 softmax behave differently after CPU touches
   versus attempted L1 eviction? Measure first=0 and first=1 separately.
2. Is the EVU penalty concentrated in bulk bandwidth or 4-byte command
   round trips? Compare load/store/copy and 4/16/64-byte state copies.
3. How much work can the existing execution paths sustain without timed
   memory accesses? Measure preloaded max, sum, EXP2/shift, and a combined
   max -> shift -> log2(e) multiply -> EXP2 -> sum chain.

These are diagnostic comparisons, not a subtraction-based decomposition of
softmax cycles. Different stalls overlap and cannot simply be added.

## Build and run

Append the same server toolchain overrides used for the existing tests:

```sh
# In XSAI_external_vector:
make -C software/evu ARCH=riscv64-xs NAME=vector_memory_path_evu_test ...
./build/emu --diff /path/to/NEMU_evu/build/riscv64-nemu-interpreter-so \
  -C 100000000 -i software/evu/build/vector_memory_path_evu_test-riscv64-xs.bin \
  2>&1 | tee evu_memory_path.log

# In nexus-am/tests/exp2test: use the target name, not NAME=.
make ARCH=riscv64-xs vfexp2_memory_path_matched ...
# Run with the corresponding core-only XSAI emu and reference:
./build/emu --diff /path/to/NEMU/build/riscv64-nemu-interpreter-so \
  -C 100000000 -i /path/to/nexus-am/tests/exp2test/build/vfexp2_memory_path_matched-riscv64-xs.bin \
  2>&1 | tee rvv_memory_path.log
```

The default EVU image keeps the ordinary unsuffixed name. Nondefault options
use separate object directories and a configuration-suffixed image path,
printed by the build; do not accidentally run an older default image.
The RVV target links the same vendor kernel dependencies as the existing
matched softmax. Neither primary test's defaults are changed.

## Controls and cases

Both Makefiles accept the following options; no manual macro edits needed:

| Option | Default | Meaning |
| --- | --- | --- |
| `MEM_PATH_GROUP` | 0 | 0 all, 1 softmax/cache probe, 2 memory, 3 resident computation |
| `MEM_PATH_CASE` | 0 | 0 group selection, or one numbered case below, overriding the group |
| `MEM_PATH_CACHE` | -1 | -1 both preparations, 0 CPU touch, 1 attempted eviction |
| `MEM_PATH_EVICT_KIB` | 256 | Independent, 4-KiB-aligned eviction buffer size |
| `MEM_PATH_EVICT_PASSES` | 2 | Forward/reverse cache-line scans outside the timer |
| `MEM_PATH_ITERS` | 8 | Resident compute repetitions; no repeated memory traffic |
| `MEM_PATH_WARMUP` | 1 | One unreported warmup per selected case, followed by full reset |
| `MEM_PATH_TRANSPORT_CHECK` | 0 | Optional post-timing bit checks for copy/state/store, no FP64 |
| `MEM_PATH_RESIDENT_VL` | 128 | EVU only: 128 for its native shape, 16 for same-size RVV diagnostics |

For a short first experiment use `MEM_PATH_GROUP=1` on both engines. Then
use group 2; finally group 3. To isolate one operation use e.g.
`MEM_PATH_CASE=7 MEM_PATH_CACHE=0`. Print volume is one line per measurement,
not per row, iteration, or cache line. The all-case default has 24 result
lines plus three header/footer lines. All initialization, eviction sweeps,
snapshots, optional checks and printing are outside measured intervals.

| ID | Printed key | Work inside completed timer |
| --- | --- | --- |
| 1 | soft0 | Existing 32x256 update with carried row state |
| 2 | soft1 | Existing 32x256 first update |
| 3 | cpu_probe | CPU volatile 4B load per 64B line of the 32-KiB score array |
| 4 | load | 32-KiB sequential native-vector load stream |
| 5 | store | 32-KiB sequential stores of preloaded uniform 1.25f |
| 6 | copy | Read 32 KiB, write 32 KiB to a separate buffer |
| 7 | state4 | Copy 128 FP32 states using 4B per memory instruction |
| 8 | state16 | Same 128 states, 16B per memory instruction |
| 9 | state64 | Same 128 states, 64B per memory instruction |
| 10 | exp_shift | Four resident vectors: subtract a fixed row max, then EXP2, repeated |
| 11 | max | Repeated independent max reductions of four resident vectors |
| 12 | sum | Repeated independent sum reductions of four resident vectors |
| 13 | chain | Four-row max/shift/multiply/EXP2/sum dependency-chain diagnostic |
| 14 | line_load | 32-KiB loads with 64B per instruction on both engines |

The pure memory tests use a deliberately simple, single-register ordered
stream, not either engine's maximum-throughput hand-tuned memcpy. Instructions
may carry destination-reuse dependencies; this tests command-path cost too.
Case 14 helps distinguish an EVU 512B-command advantage from cache-path
differences. Case 7 measures vector small-access overhead, not RVV's best
scalar-FPU implementation. Cases 8/9 need no packing because their source
states are already contiguous; they do not prove real softmax's scattered
reduction outputs can be packed at zero cost.

## Timing and interpretation

`c` includes work and completion synchronization; `i` is retired CPU
instructions. `b` is useful payload, read+write for copies; `bc` is payload
bytes/cycle, not bus bandwidth. A probe reads 2048 useful bytes while touching
512 lines. `vm` is a source-derived vector-memory-instruction count,
not physical TL transactions; -1 means not statically reported. `n` reports
processed elements. `t=begin:end` are guest rdcycle timestamps for locating
the workload; hardware log timestamps may have an offset. `fl` is FP flags;
`err=0` with check=0 is not a numerical softmax accuracy PASS.
The empty synchronization measurement is diagnostic, not subtracted from c.

Preparation uses CPU reset/stores, then volatile cache-line reads. `cpu_touch`
does not guarantee every target line is in L1, especially a 64-KiB copy
working set plus other data. `evict_try` scans the independent buffer after
the same preparation; neither fence nor this scan proves eviction or L2 hits.
Current source DefaultConfig uses a 64-KiB 4-way L1D and 1-MiB banked L2, but
the server's actual elaboration may differ. Sweep 128/256/512 KiB as needed,
and validate misses/probes before calling it an L1-cold/L2-hot result.
Changing the buffer size/layout can also change conflict behavior.

Cache preparation can change translation/TLB and prefetch state too. In
particular, a large eviction scan may displace data translations. Interpret
these results as cache/coherence-path diagnostics until those effects are
checked; do not call the difference a pure SRAM access-latency measurement.

If L1 eviction slows RVV much more than EVU, L1 residency is important. If
EVU improves after eviction, CPU dirty-line probes/writeback may be important.
Compare first=0/1 independently and compare the 4/16/64B payload cases.
HBL2 accesses are coherent and need not go to DRAM.

EVU resident vectors use four x 128 FP32 (2048B), RVV uses four x 16 FP32
(256B). They are native-shape diagnostics, not identical-workload latency
comparisons: normalize by n and account for different reduction lengths.
Repeat EVU group 3 with `MEM_PATH_RESIDENT_VL=16` for a same-size comparison.
Each engine loads before timing and snapshots after timing. RVV explicitly
extracts four results to integer registers before ending the timer; EVU
XV_FENCE drains arithmetic. These completion costs remain included.
The chain omits cross-tile old-state/alpha publication and is not a complete
online softmax. Its data are reused between repetitions, not reset by loads.
EXP2-only repetition without a shift would eventually overflow; case 10
therefore intentionally includes a subtract and is named `exp_shift`.

## Hardware counters: limitations

No guest-visible EVU performance-counter CSR ABI currently exists; this test
does not invent one. `hwc=emu_global` explicitly says the existing emu EVU,
L1/L2 and probe counters remain whole-program aggregates and include resets,
warmup, eviction, printing and checks. Selecting one case makes them easier
to interpret but does not turn them into region deltas. For strict attribution,
capture snapshots at the measured begin/end PC or add region-scoped RTL
instrumentation in a later change. Request count, bus wait, memoryCanStart
blocking, and L1/L2 hit/miss/probe timing must be measured there. Do not infer
that memory_order_stall=0 means the outer VMU gate never blocked.

No emu, binary build, or Verilog generation is performed by this change.
Both entries pass strict RISC-V C syntax checks. The local stock clang lacks
the server's custom EXP2 assembler; an additional RVV assembly-generation
check substitutes a standard unary FP opcode only in temporary preprocessed
input to inspect register constraints and completion ordering. That check
does not validate EXP2 encoding, numerical behavior, or runtime timing.
