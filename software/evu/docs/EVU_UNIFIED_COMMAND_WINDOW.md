# Unified command window

The pre-change server baseline is checkpoint `51b801f1f` on
`feat/evu-optimization`: fused row-major RMSNorm/NVFP4 passes at 4054 cycles,
1668 CPU instructions, 1596 EVU completions and 18 stores. This checkpoint was
pushed before the unified-window implementation. Its end-of-guest wait counters
are not exact kernel FENCE deltas.

## Implementation

Production `EvuTop` now allocates one 32-row `EvuCommandTable`. Its row number
is both the CPU-facing job ID and the internal execution VAT. All commands,
including configuration, FENCE and immediate/illegal completions, allocate in
the same order. The table stores identity (PC, opcode, destination, epoch) once;
it does not store a redundant ID in each row. Results update only outcome fields.
CPU completions remain ordered and registered, with the ID generated from the
head row. The extra accepted-ID FIFO and the 16-entry completion FIFO are removed.

The production backend no longer allocates its own VAT or stores a VAT-to-job
mapping, PC/opcode/destination/epoch arrays, or metadata-live array. Epoch on its
memory requests is the current command epoch; flush resets backend state and
the existing physical memory cancellation/drain machinery remains responsible
for old responses. Per-command page maps, memory error snapshots, pending
completion events, arithmetic flags and the cross-command scoreboard remain.
Backend-only legacy harnesses retain local VAT allocation unless explicitly
instantiated with unified commands; production Top always enables it.

An internal scheduling-active bit is separate from the command's live/completed
bits. Store-data release removes its internal oldest-reader priority before
physical memory Ack, while its command row remains allocated. Arithmetic and
load scheduling releases similarly retain their existing completion boundary.
The oldest scheduling-active row is selected in circular allocation order; gaps
from control commands and tag wrap are supported. Completion reporting cannot
extend the underlying RAW/WAR/WAW reservations or collector validity rules.

At a full window, a row that completes the CPU handshake can be reused by a new
command in the same cycle. Allocation wins state updates over retirement, with
flush taking final priority. The old completion remains visible for that cycle;
the new identity becomes visible after the edge. Results must match a live row
and epoch; wrong-epoch responses drain without updating the row. A row is not
reallocated before its physical completion and ordered CPU handshake.

The unified record is 156 declared bits (79 identity + 77 outcome), or 4992
bits across 32 rows, excluding live/completed/scheduling/state bits. This is
logical capacity, not a synthesis area result. The scoreboard also grows from
16 to 32 entries; the VRF and arithmetic units do not grow. `completionDepth`
remains accepted by old constructors, but allocates no FIFO in production Top.

## Server comparison

The supplied November 18 server log passes at **3747 cycles**, versus 4054 at
the pre-change checkpoint: 307 fewer cycles (7.57%). CPU instructions remain
1668, EVU completions 1596 and physical stores 18; errors=0, GOOD TRAP and
pending=0. The guest and arithmetic issue counts are unchanged. This is the
combined unified-window result, not an isolated attribution to its three changes.
The log lacks hardware FENCE snapshots and `[EVU-CONFIG]`; its wait counters are
end-of-guest totals. See [EVU_CHECKPOINT_2026-11-19.md](EVU_CHECKPOINT_2026-11-19.md)
for the exact evidence, retained limitations and cleanup inventory.

Use the exact same guest binary and NEMU library as the checkpoint; no software
rebuild or ISA change is required. Build/run on the server only. The default is
`DefaultExternalVectorMatrixConfig`; use
`DefaultExternalVector16CommandMatrixConfig` to change only the unified window
capacity to 16. Compare that control with the old checkpoint to isolate most
structural effects, then compare unified 16 versus 32 for window capacity.
The unified 16 control also narrows its internal age tags to four bits.

Run the existing fused workload with `-- +evu_mem_trace=1`. At each FENCE,
`[EVU-CONFIG]` includes `command_depth=32 unified_commands=1 completion_fifo=0`
(or `command_depth=16` for the capacity control). Require PASS, GOOD TRAP,
pending=0, unchanged work counts and bit-exact output before comparing cycles.
Use the existing RMSNorm counter summarizer for the timed FENCE pair.

Compare cycles, job-full and head-blocked waits, AMU issue-window stalls,
integer/FMA idle-with-work and VRF conflicts. `job_full_release` should be zero
when the retiring full-window row can be reused; it is no longer a count of all
raw full-window retirements. Lower job-full alone does not establish a speedup.
The internal completion arbitration remains one result per cycle and stores
non-selected results until accepted; removing the FIFO does not double throughput.

## Local validation

Only Scala compilation, focused Treadle interpretation and in-memory CHIRRTL/HW
checks are used locally. No Verilog, emu or guest binaries are generated.
Focused tests cover full 32-row occupancy, reversed completion order, row reuse
on consecutive retirements, flags/fault ownership, completion backpressure,
early internal store release, flush/epoch protection, and external dispatcher
tags across wrap and skipped command IDs. Production CHIRRTL checks verify the
VRF SRAM is retained and removed metadata/FIFO structures are absent. These
checks do not replace the server Difftest workload or timing/area synthesis.

Validation completed: full `xiangshan.compile` and `evuMmuCheck.test.compile`;
15 focused Treadle cases (six command-table, seven scoreboard, one job-counter,
one unified-dispatch); the production CHIRRTL structure check; and the
production HW-dialect combinational-cycle check. Existing enum/index-width
elaboration warnings remain. No local emu, Verilog or guest bin was produced.
