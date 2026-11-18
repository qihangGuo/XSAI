# Direct-slide prefetch and E0 moves

Source candidate after checkpoint `1f274d38d` (4183 server cycles). No server
measurement of this candidate exists yet. Software and RTL changes are
independent; measure the same guest on both hardware configurations before
attributing any gain.

## Hardware

`EvuParams.slidePrefetch=true` permits existing aligned and single-group
slide fast paths to prefetch their first source group from the issue-queue
head while the current instruction is at its tail. Source selection matches
ExecuteSequencer: aligned slidedown starts at source offset, aligned slideup
starts at source element zero, and a group-local slide reads the first group.
Scalar gather retains its existing indexed source. Complex permutations,
masked instructions, reductions, nonzero vstart and zero VL remain excluded.

The oldest-writer test retains circular VAT ordering against all queued,
active, pipelined and staged writers. Physical-write and bypass invalidation
in RegisterReadCollector is unchanged. Hints use existing speculative read
ports, arbitration and buffers; demand reads retain priority. No arithmetic
unit, VRF bank/port, queue depth or retirement rule changes.

Use `DefaultExternalVectorMatrixConfig` for the candidate and
`DefaultExternalVectorNoSlidePrefetchMatrixConfig` to disable only slide
prefetch, retaining the new diagnostics on both. Both retain two ordinary FMA
contexts and the two-entry dispatch adapter. `[EVU-CONFIG]` prints
`slide_prefetch=1` or `0` with `-- +evu_mem_trace=1`.

## Software

Only `rmsnorm_nvfp4_fused_evu_test.c` defaults the two new options to one:

- `EVU_RMS_QUANT_DIRECT_MAXIMA`: the first reduction of each batch writes
  maxima[0] directly; skip its zero-offset slide. Other maxima insertions keep
  their order and overwrite the remaining positions before use.
- `EVU_RMS_QUANT_SCALAR_FIRST`: first packing multiply uses `FMUL_SCALAR` on
  inverse[0], skipping its broadcast gather. Keep the zero-block mask,
  rounding, adjacent-pair order and full-line output stores unchanged.

With one sample and direct-first extraction enabled:

| Direct maxima | Scalar first | Quant commands | Timed commands | Guest completions | Integer issues |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 0 | 1504 | 1610 | 1612 | 902 |
| 1 | 0 | 1496 | 1602 | 1604 | 894 |
| 0 | 1 | 1496 | 1602 | 1604 | 894 |
| 1 | 1 | 1488 | 1594 | 1596 | 886 |

All four retain FMA/CMP/FP8/FP4 issues 684/272/16/128, DIV issues 32,
memory Get/Put 171/18, 1152 output bytes and exact checking. No ISA or NEMU
change is needed. With direct-first disabled, add 32 integer instructions and
commands to each row. The original multi-case test defaults both new options
to zero and retains its original schedule.

Build on the server with the normal command for the default candidate:

```sh
make -C software/evu ARCH=riscv64-xs TOOLCHAIN=LLVM NAME=rmsnorm_nvfp4_fused_evu_test
```

For the checkpoint software control, append
`EVU_RMS_QUANT_DIRECT_MAXIMA=0 EVU_RMS_QUANT_SCALAR_FIRST=0`. For isolated
experiments set just one option to zero. Variant object directories and
nondefault binary names include both flags; use the path printed by make.
The guest header prints `quant_direct_maxima` and `quant_scalar_first`.

## Counters

Integer (`int`) and divider (`fp_div`) now have FU issue,
dependency/operand/feedback/permutation/writeback/FU/queue waits and idle diagnostics.
`busy` means at least one sequencer or arithmetic pipeline is active, excluding
queue-only work; divider busy includes executing and held results, while its
FU wait describes a ready next operation waiting on the current iterative unit.
Existing `no_work` semantics are unchanged and must not be subtracted from busy.

`evu_dispatch_blocked_cycles` counts valid public dispatch heads that cannot
advance. `evu_dispatch_<path>_queue_blocked` identifies a full destination issue
queue, including load/store/special/integer and every split FP path. Reduction
owner exhaustion remains `evu_reduction_full_cycles`. One reduction targets
both special and arithmetic queues; reasons can overlap and must not be summed.

`evu_job_head_blocked_cycles` counts an unfinished oldest accepted job while a
younger live job already has a buffered completion. A ready head stalled by
the external receiver is excluded. `evu_job_full_release_cycles` counts a
waiting command without a reusable slot in the same cycle an ordered completion
fires; it measures existing credit timing, not a new credit bypass.
Both new job diagnostics are enabled only with Difftest diagnostics. The
existing job-full counter retains its original meaning and flush behavior.

With `-- +evu_mem_trace=1`, new `[EVU-JOB]` and `[EVU-DISPATCH]` records and
FU `busy`/HANDOFF `slide` fields permit FENCE interval differences:

```sh
./build/emu --diff ../NEMU_evu/build/riscv64-nemu-interpreter-so \
  -C 100000000 -i software/evu/build/rmsnorm_nvfp4_fused_evu_test-riscv64-xs.bin \
  -- +evu_mem_trace=1 > rmsnorm-slide-prefetch.log 2>&1
/usr/bin/python3 software/evu/scripts/summarize_rmsnorm_counters.py rmsnorm-slide-prefetch.log
```

Use unchanged seeds, image and emulator options for each isolated hardware
comparison. Require PASS/GOOD TRAP, no pending events and the expected work
counts before comparing elapsed cycles. Wait counts overlap and prefetch
opportunities are not successful read counts or promised saved cycles.

## Local validation

Fifteen Treadle tests cover first-source selection, optional disable, VAT wrap,
older writers, excluded complex/masked/tail cases, ordered-job pressure, and
the existing SRAM collector rewrite/E0/external-bypass invalidation scenarios.
The SRAM test helper now uses a distinct macro suffix for external bypass to
avoid reusing an imported SRAM definition between different elaborations.
Seventy software preprocessor/syntax/model/parser tests pass, including each
new software option separately and combined across all bank/rename settings,
poisoned tails, zero blocks and scale ranges. The symbolic test verifies all
18 stores retain identical byte dependencies. Main/core Scala compilation
and full DLEN=512 diagnostic-top CHIRRTL elaboration pass. CHIRRTL elaboration
does not check final physical timing or replace server Difftest.
No emu is built or run, and no Verilog or guest binary is generated locally.
