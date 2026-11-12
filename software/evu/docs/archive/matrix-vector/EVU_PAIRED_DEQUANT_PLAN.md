# Paired-row dequantization and demand-read scheduling

Date: 2026-11-09. Target: DefaultExternalVectorMatrixConfig, DLEN/MLEN=512,
VLEN=4096, 16 KiB VRF, 1RW banks, memory command depth four.

Keep guest ISA, NEMU arithmetic, CPU functionality, HBL2 and CUTE computation
unchanged. CUTE additions are simulation-only probes before ready/valid gating.
Do not run Java/Mill, generate RTL, build bins or execute emu locally.

## Phases

1. Restore one ordinary FMA context. Serialize tiles directly from a two-slot
   queue without a third active copy; pair two rows inside the same microtile.
2. Select repeated column scales and two row scales, preserving both rounded
   multiplies. Carry per-row flags in one paired difftest event; its checker
   invokes the existing NEMU row callback twice, without changing reference ABI.
3. Accept two aligned 32-byte row fragments into distinct line-buffer entries
   in one cycle. Keep a serial fallback for conflicts/unaligned data. Publish
   completion only after real acknowledgements, including errors and flush.
4. Add diagnostic bridge occupancy, raw MTE blocking and dequant wait counters;
   report cumulative FENCE snapshots and per-launch summaries. Peaks are per
   task, not differences between cumulative maxima.
5. Add demand aging and a registered-state last-operand hint through both VRF
   arbitration levels, preserving demand-before-prefetch and reserved writes.
   Reuse the existing source collector for a dependency-checked first-EG EXP2
   hint at the previous tail. Add reduction-tail diagnostics before a new
   arithmetic pipeline or any change to floating-point operation ordering.

## Validation

Locally: `git diff --check` and Python parser regressions only.
Server: `mill -i evuCheck.test.testOnly evu.MatrixVectorBridgeSpec`,
`mill -i evuCheck.test.testOnly evu.EvuMatrixDequantSpec`,
`mill -i evuCheck.test.testOnly evu.EvuMatrixStoreBufferSpec`,
`mill -i evuCheck.test.testOnly evu.EvuReadPrioritySpec`, plus existing memory
writeback, FMA ownership, area, flags and flush/FENCE regressions.

Rebuild emu with the original DefaultExternalVectorMatrixConfig command and
reuse ABI2 bins/NEMU. Run softmax and FlashAttention with +evu_mem_trace=1.
Pass: correct paired-row addresses/flags, no dropped or duplicate transactions,
GOOD TRAP, difftest pending=0, and independent numerical checks. Record performance
without claiming a speedup before server measurements. Use separate server
revisions for FMA restoration, paired rows and read arbitration when attributing
individual effects. Baseline softmax cycles: 6624/5437.
