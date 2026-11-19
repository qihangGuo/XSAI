# Online Softmax On Current EVU

## Contract And Schedule

`online_softmax_evu_test.c` is compatible with current ABI 2 and the unified
32-command window; no instruction adaptation is required. It updates online
max/sum/alpha and overwrites scores with unnormalized exponentials. It does
not divide scores by the final sum. For positive finite scale, each row computes
the new maximum, rescales the previous sum with alpha if `first=0`, and sums
new exponentials. `first=1` has no previous-state reads and stores alpha=0.

Default dimensions are 32x256 FP32, stride=256, scale=1. Each row's two
128-element chunks remain in VRF. `wave4x2` alternates v0..v7 and v8..v15:
four current rows plus four prefetched rows. v16..v19 hold raw max, new max,
exponent bias and sum in sequence; v20..v23 hold old max/alpha/rescaled old
sum. v24/v25 are reused old-sum temporaries, v26 is unused, and v27..v31
hold constants/seeds. There are no default scratch stores, slide or gather
commands. Four independent rows overlap the two reduction owners and two
ordinary FMA contexts; eight fully active rows would exceed the current
register allocation once state and constants are included.

Scalar state does occupy architectural vector register names, but E0 and
scalar-source forms consume only element zero and use the current short-result
and E0 paths. This does not imply every scalar arithmetic operation reads or
writes a full 512-byte vector. Reuse still creates real register-name hazards.

## Exact Default Work

`scripts/check_online_softmax_schedule.py` preprocesses and interprets the
actual C macros and asserts the following work counts. It checks 182 schedules
without producing binaries. The ideal EXP2 model does not prove DUT accuracy,
unordered-sum bit equality or timing.

| Commands inside `online_update` | `first=0` | `first=1` |
| --- | ---: | ---: |
| Configuration | 1 | 1 |
| Score loads / stores | 64 / 64 | 64 / 64 |
| E0 constant and old-state loads | 69 = 5+32+32 | 5 |
| E0 output-state stores | 96 = 32x3 | 96 |
| Pairwise vector max / max reduction | 32 / 32 | 32 / 32 |
| Vector multiply / subtract / EXP2 | 64 / 64 / 64 | 64 / 64 / 64 |
| Pairwise vector add / sum reduction | 32 / 32 | 32 / 32 |
| E0 max / subtract / multiply / EXP2 / add | 32 / 32 / 96 / 32 / 32 | 0 / 0 / 32 / 0 / 0 |
| Total | **838** | **582** |

These exclude caller FENCE/CSR commands and CPU scalar setup instructions.
Each 128-element vector operation comprises eight 16-element EG issues.
With the default four accumulator slots, each sum reduction needs eight
stream issues, three slot merges and four upper-half folds: 15 FMA issues.
Each max reduction uses eight stream issues, three merges and one tree issue:
12 CMP issues. The single-EG direct-min/max path requires VL=16 and therefore
does not apply to these VL=128 reductions.

Expected per-call FU issues are FMA=1920/1792, CMP=672/640 and EXP2=544/512
for first=0/1. For example FMA first=0 is 32x(5x8+15+5)=1920: five ordinary
long-vector operations per row, sum reduction, and five scalar operations.
The EXP2 count is 32x(2x8+1)=544. These are useful work budgets, not total
kernel cycles. The shared FMA can issue at most once per cycle, so 1920/1792
are resource-only lower bounds; memory, command supply, true dependencies,
feedback and the final drain prevent treating them as achievable timings.

Score traffic is already one 32 KiB read plus one 32 KiB write. State/constant
payload adds 660 B for first=0 or 404 B for first=1. Small state commands can
be expensive even though their bytes are few; physical line coalescing and
write Acks determine their true cost, not just the payload size.

## What To Optimize

The present schedule is a reasonable baseline, not a proven optimum for the
new command window. First run CHECK=1 for both first modes, then collect formal
FENCE deltas separately. Default WARMUP=1 and DRAIN_DIAG=1 execute three kernels
per mode (six total), so whole-guest counters do not describe a single run.
For an isolated profile use FIRST=0 or 1, WARMUP=0, DRAIN_DIAG=0, CHECK=1;
use the Makefile's printed variant-specific image path. Obtain warm results
separately when comparing with a warmed historical measurement.

First software experiment: move old-state loads earlier and revisit the two
reused old-sum temporaries using free v26. Keep every row's arithmetic order
and rounding unchanged. With two FMA contexts and 32 commands in flight, the
old schedule's pair spacing may no longer be best; sweep small pair-order and
prefetch-distance changes while measuring FMA/EXP2 idle work, reduction-full,
VRF waits and memory/compute overlap. Avoid assuming all extra lookahead is
useful or doubling active rows without a register-lifetime analysis.

Second experiment: reduce state-command overhead only if interval memory
counters justify it. The existing STATE_LOAD_BATCH=1 saves 48 memory commands
(293 to 245) for first=0 but adds 64 gathers and 16 configuration commands:
total commands rise 838 to 870, with two draining VL changes per four rows.
STATE_BATCH=1 additionally packs through four scalar scratch stores and a
load, not register-only packing. Neither flag is an automatic optimization.
A new register-only state-packing experiment would still require short-store
configuration/restore costs and temporary registers; evaluate the complete
command/critical-path cost before implementing it.

The bank mapping folds all five register bits. For group zero, current
(head,tail,bias) banks are A rows (0,1,3),(1,0,2),(2,3,1),(3,2,0), but B rows
(2,3,3),(3,2,2),(0,1,1),(1,0,0). Thus the old comment about all biases differing
from both chunks is not universally true. However scalar bias can hit the E0
mirror and avoid SRAM altogether. A B-only scratch renaming experiment must
measure actual grant/write conflicts and preserve inter-wave hazards; this
mapping observation alone does not establish a bottleneck.

Do not replace multiply-plus-subtract with FMA, alter sum association or use
approximate exponent identities when comparing identical arithmetic. Such
changes affect rounding/flags and need an explicitly different numerical
contract. At scale=1, score multiplication still converts to base-2 EXP2 input
using log2(e); it is not an unnecessary multiply by one.

## Historical Timing Boundary

The retained November 11 server log `evu_softmax_512_before.log` reports
first=0: 6720 cycles (warmup 6850); first=1: 5441 (warmup 5623), GOOD TRAP
and pending=0. That image had CHECK=0 and a separate diagnostic run; it is
neither a numerical-check result nor a measurement of the current unified RTL.
The older 7624/6126 checkpoint is also historical. Current performance remains
unmeasured until the present hardware runs this benchmark.
