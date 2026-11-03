# EVU 512-bit datapath

The default `DefaultExternalVectorMatrixConfig` now uses DLEN=512 and MLEN=512.
The software instruction encodings, VLEN=4096, register count and timing
boundaries are unchanged. No alternative top-level configuration is required.

| Resource | Previous | Current |
| --- | --- | --- |
| Compute beat | 256 bits / 8 FP32 | 512 bits / 16 FP32 |
| Ordinary VMU beat | 32 bytes | 64 bytes |
| Architectural VRF | 32 x 4096 bits | 32 x 4096 bits |
| Physical VRF | 4 lanes x 8 banks | 8 lanes x 4 banks |
| SRAM macros | 32 x 64 words x 64 bits | Unchanged |
| SRAM ports | 1RW, byte masks | Unchanged |
| Ordinary memory command window | 4 | 4 |
| Reduction contexts / partial slots per context | 2 / 4 | 2 / 4 |
| CUTE AB entry write port | 256 bits / 32 bytes | Unchanged |

FMA, comparison and EXP2 instantiate their slices from DLEN, as do the integer,
conversion and permutation paths. Expanding DLEN therefore also expands those
paths, operand collectors, queues and interconnect. Peak FP32 element throughput
doubles for the expanded elementwise units; whole-operator speedup and frequency
must be measured. No extra execution contexts or independent FMA path are added.

VRF reads and writes span eight 64-bit lanes. Each lane now has four banks;
the existing XOR bank mapping uses that bank count. The number of independently
arbitrated bank groups decreases from eight to four, so contention can change
even though aggregate beat width doubles. This is a new organization of the same
32 SRAM instances, not an additional port on any SRAM macro.

## Four-bank register hash

The follow-up bank mapping changes only the 512-bit, four-bank organization.
Let `reg = eg / (VLEN / DLEN)`. The bank is now:

```text
bank = (eg XOR reg XOR (reg >> 2) XOR (reg >> 4)) AND 3
row  = eg >> 2
```

The old mapping used only `eg XOR reg` for the bank. Consequently, the paired
registers `v0/v4`, `v1/v5` and `v8/v12` selected the same bank for corresponding
chunks. Folding the high register bits separates these pairs without changing
any SRAM row address, depth, width, capacity, port or instance count. Other
datapath/bank configurations retain their previous mapping. Reads, pipeline
write reservations, physical writes and staged load commits all use the same
`vrfBank` helper. No software register assignment, instruction, arithmetic or
prefetch policy changes.

With `-- +evu_mem_trace=1`, `[EVU-EXE]` snapshots additionally include:

- `bank`: cumulative cycles with different-row reads competing in at least one
  bank group that is not writing.
- `wrblk`: cumulative cycles with a write blocking reads in at least one bank
  group.

These are existing counters, not new state or scheduling decisions. They can
overlap across different groups; do not add them as independent lost cycles.
The snapshot prints are simulation-only. Use the updated summary script:

```sh
python3 software/evu/scripts/summarize_softmax_fences.py evu_softmax_512_after.log
```

The script handles padded hardware fields and interleaved UART/simulator
records, and reports formal VMU, execution and FU counter differences. Old logs
without `bank`/`wrblk` still parse; unavailable counters are omitted, not zeroed.
The pre-change `evu_softmax_512_before.log` reference is 14025/12528 cycles for
first=0/1, with formal FENCE intervals 2:3 and 9:10. Rebuild only the emulator
for this hash change; reuse the existing 512-bit NEMU library and guest binary.

The hash follow-up passed 24 focused Scala tests: exhaustive bank/row mapping
for six geometries, physical 512-bit paired reads and masked writes, staged load
commit attribution, softmax RTL/NEMU snapshots, FP16/FP32 reductions and EXP2,
memory tags/tails/errors, quantization, legacy read arbitration, E0 forwarding
and the area boundary. All 690 softmax instruction snapshots and 112 vector
stores still match NEMU; replaying the RTL trace with three initial flag states
also passed. Main/test compilation and eight summary-script tests passed.
The unchanged pre-change log is successfully parsed by the updated script.
Whole-kernel performance and full FlashAttention still require the rebuilt
server emulator; these correctness checks do not establish a speedup.

## Data and Reference

An FP32 VL=128 reduction now streams eight 16-element groups instead of sixteen
8-element groups. Four partial slots and their merge order remain; the final
fold gains one level. Unordered floating-point sums can change low bits because
the lane grouping changes. NEMU must use the same DLEN, without relaxing
correctness tolerances to hide differences.

Quantization converts sixteen FP32 elements into sixteen INT8 bytes per beat.
The bytes are placed in the proper half of the fixed CUTE entry using a byte
mask. Tail lanes do not write data or contribute flags. CUTE result tiles retain
their 8x8 microblock boundaries; a result row has eight active low lanes in a
512-bit event. Matrix dequantization, stores and the reference model respect
that mask. HBL2 already transfers 64-byte lines; wider aligned full stores use
that existing interface, while partial/unaligned stores retain guard-preserving
handling and physical-Ack completion.

## Rebuild

Build the emulator with the existing command:

```sh
make emu CONFIG=DefaultExternalVectorMatrixConfig \
  ACCELERATOR_CDC_ENABLE=0 EMU_THREADS=8 EMU_TRACE=fst -j16
```

Synchronize the modified NEMU repository to the server's `NEMU_evu`, then rebuild:

```sh
make git_commit= riscv64-xs-evu-ref_defconfig
make git_commit= clean
make git_commit= -j8
```

The reference configuration now selects `CONFIG_CUSTOM_EVU_DLEN=512`.
An old 256-bit NEMU library is not a valid reference for the new reduction tree
or quantization event grouping. Existing guest binaries can be reused: this
change does not alter their instruction encodings or software algorithm.

Standalone area RTL must also be regenerated before synthesis. Its manifest
must report DLEN/MLEN=512, eight lanes, four banks per lane, 32 macros,
64-word depth, 64-bit width and 131072 VRF bits. SRAM blackboxes still exclude
real macro area and timing.

## Validation

Validated locally on 2026-11-02:

- Main and test Scala compilation passed.
- NEMU native compute, scalar/E0, memory, quantization and matrix reference
  regressions passed with DLEN=512.
- The seven-case softmax RTL/NEMU comparison matched all 690 instruction
  snapshots, 112 vector stores and completion flags. Replaying the RTL trace
  into NEMU also passed with three different initial scalar flag values.
- EXP2 integration passed for FP16/FP32, masks, vstart, aliases, E0 chaining,
  backpressure and flush. Reduction integration passed cancellation, four-slot
  accumulation, tail preservation, flags and the numerical softmax case.
- Ordinary 64-byte VMU integration passed queue/tag wraparound, reordered
  responses, delayed final Ack and bus errors. Byte/halfword/word tail stores
  passed on both sides of the 64-byte boundary without changing guard bytes.
- Quantization integration passed VL tails around 8/16/32 elements, inactive
  signaling-NaN lanes and VRF preservation. Quantization, dequantization,
  matrix bridge, store buffer, tag router and HBL2 adapter unit suites passed.
- Area boundary checks passed with diagnostics excluded. Actual SystemVerilog
  export and `prepare_rtl.py` completed; the exported `AraRegisterFile` has
  exactly 32 SRAM instances, `storage_0_0` through `storage_7_3`. The manifest
  confirms the unchanged SRAM shape, capacity and 1RW ports.

Run SRAM-backed test suites in separate Mill invocations: the SRAM utility
caches definitions within a JVM. Set `CCACHE_DISABLE=1` for these Verilator
regressions because cached precompiled headers can refer to a previous Mill
temporary directory. The existing `check_softmax_ref.sh` handles these rules.

No full-core emulator timing run or standard-cell area/timing synthesis was
performed locally for this expansion. Measure online softmax and FlashAttention
on the rebuilt server emulator with the same guest binaries and completion
boundaries as the 256-bit baseline.
