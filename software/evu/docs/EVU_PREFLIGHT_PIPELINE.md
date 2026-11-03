# EVU address-preflight pipeline

## Baseline and scope

The November 3, 2026 11:22:33 emulator in `evu_softmax_512_before.log` reports
13,987 cycles for first=0 and 12,581 for first=1. Full-group forwarding has
zero hits. Formal FMA issue counts are 1,920/1,792, with 10,819/9,731 cycles
without work in that issue queue or its sequencers. The widened arithmetic
units are not continuously supplied with work.

The old CPU-side preflight checked one line at a time through
idle -> send -> waitTlb -> waitPmp -> respond. Every store requested a read
check followed by a write check, including aligned full-line stores that never
perform an external read/modify/write. The scalar EVU producer waits for this
preflight before writeback. EVU-side entry-stall counters do not cover it.

For the unchanged default workload, the expected numbers of logical checks
were 1,797/1,733. These are source-derived counts, not measured stall-cycle
decompositions. The new preflight counters allow the server run to verify them.

## Implementation

`EvuAddressCheck.scala` generates consecutive line/permission requests into
an eight-entry metadata FIFO. Each response uses its saved virtual address
and permission type to update the command's page map and fault information.
After an ordered fault, new checks stop and every accepted check drains before
the command can return its first fault. No vector memory side effect is allowed
before the complete range has passed preflight.

`EvuPreflightPipeline.scala` retains eight independent check records. The
existing dedicated nonblocking EVU TLB can accept one request per cycle;
its request/response `memidx.idx` carries the record ID. PMP permission results
are captured one cycle after their matching TLB response, preserving the timing
of the existing registered PMP checker. Completed younger records wait for the
oldest record. TLB misses replay their own record; they cannot borrow a younger
record's address, permission, page map or error.

`MemBlock.scala` connects this pipeline to the existing EVU TLB and PMP. It does
not add a translation port or change the shared TLB/PMP implementation. Bare
mode uses the TLB's existing physical-address path; the PMP/PMA and full-address
checks still run. Virtual accesses retain normal page/guest/access faults,
uncacheable-memory rejection and noncontiguous physical-page mappings.

On redirect/cancel, request records and pending range metadata are discarded.
The pipeline blocks slot reuse for three cycles, covering the dedicated
nonblocking TLB's one-cycle response and the following registered PMP stage.
Neither cancelled answers nor their delayed permission results can affect a
new command. Ordinary ROB retirement protection, address-space/CSR barriers,
scalar-store-buffer draining and final EVU memory-Ack completion are unchanged.

`backend/Backend.scala` marks a store as write-only for preflight only when it
is the ordinary unmasked unit-stride custom store, MLEN=512, and its address
and length are both multiples of 64 bytes. E0 stores and all partial-line
stores retain read and write checks. This avoids 512 redundant read checks in
each default invocation, for expected new totals of 1,285/1,221 checks.

No XSAICache, HBL2 protocol, CUTE, VRF, FU, memory-command-depth, ISA, NEMU or
software-kernel change is included. The existing 512-bit reference library and
guest binary remain applicable.

## Measurement

The same server emulator build command applies. With `-- +evu_mem_trace=1`,
the CPU prints an additional `[EVU-PREFLIGHT]` snapshot after each ordered
FENCE completion. FENCE IDs follow the EVU-side snapshot numbering; younger
preflight remains blocked while that barrier completes.

| Field | Meaning |
| --- | --- |
| `cmd` | Accepted range commands |
| `busy` | Cycles with an accepted range awaiting delivery |
| `checks` / `reads` / `writes` | Logical checks accepted by the lookup pipeline |
| `fullstore` | Nonempty stores proven to have full-line coverage |
| `lookupstall` | Cycles a range check waits for a lookup slot |
| `tlbreq` / `tlbmiss` | Actual translation requests, including retries, and miss responses |
| `sbwait` | Cycles a range request waits for older scalar stores to drain |
| `outputwait` | Cycles a scalar memory command waits for a preflight result |
| `serialwait` | Cycles blocked by prior EVU control/status protection |

These counters overlap and are not an additive decomposition of kernel cycles.
Diagnostic counters and prints are enabled only in the difftest configuration.
The parser reports their formal first=0/1 differences as `[OS-PREFLIGHT]`:

```sh
/usr/bin/python3 software/evu/scripts/summarize_softmax_fences.py evu_softmax.log
```

The parser also reconnects hardware fields interrupted by UART output, such as
`no[OS] first=...` followed by `work=...`. Older logs without preflight fields
remain supported; missing fields are not fabricated as zero.

## Validation and acceptance

Added/updated Scala test sources cover pipelined consecutive checks, full-store
versus RMW permission sequences, noncontiguous mappings, ordered first-fault
drain, TLB retry, slot wraparound, downstream backpressure, PMP faults,
uncacheable memory, guest faults and cancellation before slot reuse.

Per the user's instruction, no Scala/Java build, RTL generation, emulator run
or guest-bin generation is performed locally for this change. The Scala tests
still require separate server execution; the later server log below validates
the default workload's integration and performance. Lightweight Python parser
tests pass all fifteen cases, including optional counters, table output and
the actual log's UART interruptions.

Acceptance requires the unchanged 32x256 workload, existing completion boundary
and same binary, with both first=0 and first=1 below 8,000 cycles and successful
difftest. A separate CHECK=1 run must retain the existing numerical and guard
requirements.

## Measured server result: November 3, 2026

The replacement `evu_softmax_512_before.log`, produced with the emulator built
at 14:05:50, reaches the performance target for both cases:

| Case | Previous cycles | Current cycles | Warmup cycles | Speedup |
| --- | ---: | ---: | ---: | ---: |
| first=0 | 13,987 | 7,624 | 7,844 | 1.83x |
| first=1 | 12,581 | 6,126 | 6,288 | 2.05x |

The previous column is the preceding run recorded above, not a second run
in the replacement log. The shape, wave4x2 schedule, state/load batching
settings, VL=128, warmup and completion timing settings are unchanged.

Formal counters, separated from warmup and diagnostic invocations:

| Metric | first=0 | first=1 |
| --- | ---: | ---: |
| FENCE pair | 2:3 | 9:10 |
| Address/permission checks | 1,285 | 1,221 |
| Checks omitted versus original preflight | 512 | 512 |
| Full-line stores eligible for write-only checks | 64 | 64 |
| Preflight busy cycles | 2,750 | 2,366 |
| CPU waiting for preflight | 3,286 | 2,598 |
| TLB miss / lookup-slot-stall / scalar-store-drain-wait | 0 / 0 / 0 | 0 / 0 / 0 |
| Average live VMU commands | 1.82 | 2.20 |
| Average live memory tags | 3.92 | 4.78 |
| FMA issue count | 1,920 | 1,792 |
| FMA cycles without work | 4,264 | 3,230 |
| Full-EG vector forwarding hits | 0 | 0 |

Average occupancy divides the counter delta by snapshot cycles (7,664/6,166),
not the guest's primary `c`; the snapshot and rdcycle boundaries differ by
40 cycles here. The earlier FMA no-work counts were 10,819/9,731. Unchanged
FMA/CMP/EXP2 issue counts, fewer permission checks and much less FU starvation
support improved command supply as the main explanation. Full-EG forwarding
is not responsible for this speedup. The pipeline overlaps independent line
checks; it does not add arithmetic lanes or bypass permission checks.

Preflight/output waits, VRF response waits (1,680/1,760) and remaining
partial-store RMW Gets (63/68) remain useful targets for investigation. These
events overlap; none is a promise of independently recoverable cycles.
First=1 now has 309 VMU window-stall cycles, so the existing four-command
window is exercised. This does not establish a benefit from making it larger.

The run ends with GOOD TRAP, 3,469 checked stores, 4,268 checked completions
and no pending Difftest entries. Both cases have `err=0` and `fl=1` (inexact).
It remains a CHECK=0 performance run: independent numerical/guard validation
still requires CHECK=1. No additional local hardware build or simulation was
started for this analysis.

## Readable log output

Hardware counters are padded to their fixed-width decimal representation.
UART output may interrupt a hardware row, even inside a field name or value;
merging stdout/stderr through `tee` does not make the two streams atomic.
The default end-of-run global PERF dump also accounts for most of this log.

Use the existing parser's table format to repair interleaving, remove padding
and compare only the formal intervals, while preserving the complete raw log:

```sh
python3 software/evu/scripts/summarize_softmax_fences.py \
  evu_softmax_512_before.log --format table
```

The default record format still exposes all available counter deltas. The
table adds guest timing/flags and average VMU/tag occupancy; missing optional
fields appear as `-`, never zero. Neither format requires a hardware or bin
rebuild and neither replaces checking the raw log for simulator errors.

If the existing emulator was built with `SPLITVIEW=1`, running directly in an
interactive terminal with `--splitview-log=build/logs/softmax` also separates
`uart.log` from `host.log`. Do not pipe that interactive run through `tee`:
the splitview implementation checks that stdout is a terminal. The option
alone has no effect in an emulator built without splitview support. Offline
summarization works with the current combined log regardless of that setting.
