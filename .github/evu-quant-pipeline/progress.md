# Progress

## Implementation

- Added three elastic quant arithmetic stages and two independent two-slot
  operand queues. Chunk/lane/flag metadata follows each group; completion
  includes the final accepted CUTE write. Flush clears all queue/stage valids.
- Added two dequant arithmetic stages, two output slots and one A/B scale chunk
  each. Accepted VRF writes invalidate cache entries; an outstanding operand
  collection retains VRF ownership until completed. Extended matrix hazard
  reservations to eight metadata entries, cleared on output consumption/flush.
- Removed generic EvuQuant fixed-point/unused arithmetic, execution descriptors/snapshot payloads,
  0x4c/0x4e decoding/execution and NEMU's legacy word-layout write observer.
  Reserved descriptor CSR storage/checkpoint ABI remains, with no execution use.
- Updated tests, current CSR/ISA documentation and pipeline resource accounting.
  Payload is 3696 bits = 462 bytes. FIRRTL state counting gives 5111 bits for
  the entire special sequencer, plus 266 backend scoreboard bits (~672 bytes,
  including full existing command/completion records, excluding shared VRF/HBL2).

## Completed verification

- `mill -i evuCheck.compile`: PASS.
- `mill -i evuCheck.test.compile`: PASS.
- `mill -i xiangshan.test.compile`: PASS.
- `make -j8 git_commit=` in NEMU: PASS after approved access to existing ccache.
- `make -C tools/evu-ref-test`: PASS (existing unused-parameter header warnings).
- `evu_compute_ref_test`: PASS, including all SEWs and VL=0/9 of retired 0x4c/0x4e,
  896 FP history cases, seven online-softmax cases and CSR isolation.
- `evu_fp_quant_ref_test`: PASS, 34 direct-write events and downstream PV.
- Native matrix regression: PASS, three launches/6144 beats/6144 RMW stores.
- Complete native Attention regression: PASS, two Q128/KV256/D64 cases, 27356
  instructions. Error metrics unchanged: max abs 4.290e-6/4.171e-6,
  normalized MSE 8.214117e-6/9.393455e-6 against unquantized FP64.
- RISC-V clang syntax-only, Flash Attention and CSR context guests: PASS.
- Three memory-only elaboration configurations: PASS.
- Five matrix dequant interpreter tests: PASS, including consecutive one-group-
  per-cycle cache hits, invalidation, independently returned operands, flags,
  backpressure and full-pipeline/partial-read flush.
- All 17 focused interpreter/elaboration cases have passed across the initial
  run and focused rerun. Quant arithmetic, all tested VL tails/CUTE mappings,
  backpressure, cache/flush and retired opcode checks pass. After fixing the
  monitor, a six-test focused rerun passed on the final descriptor-free command
  layout, including masked matrix lanes and extra FP-width rejections.

## Debugging

- Initial native build hit read-only ccache outside the sandbox. The approved
  elevated make completed; automatic git commits remain disabled.
- The prefetch test's monitor counted one fewer scale response after changing
  CUTE ready later in the same cycle. All eight data/write checks passed. Moved
  the ready drive before handshake sampling; the focused rerun passed.
- Extending the native retired-opcode test initially called the async completion
  helper after switching to synchronous mode. Changed the VL setup to the CSR
  API; the complete native regression passed again at VL=0 and VL=9.
- Treadle source inspection confirmed that this backend lowers to FIRRTL and
  interprets it, without invoking Verilog emission/firtool/EMU. Focused behavior
  tests therefore run locally with explicit TreadleBackendAnnotation. Full
  Saturn/Verilator tests remain server-only.

No local EMU, Verilog or guest binary is generated. Server full-core difftest,
physical timing/area and end-to-end performance remain unmeasured. No edits
outside XSAI_external_vector and NEMU, and no commit/push by this task.

## Reproducible validation commands

From XSAI_external_vector:

```sh
mill -i xiangshan.test.compile
mill -i evuCheck.test.testOnly evu.EvuFpQuantSpec evu.EvuMatrixDequantSpec evu.EvuOperandCollectionSpec evu.EvuDispatchAdapterSpec evu.EvuFpQuantElaborationSpec
# Initial run: 16/17 pass, only the monitor count fails. After correction:
mill -i evuCheck.test.testOnly evu.EvuOperandCollectionSpec evu.EvuMatrixDequantSpec evu.EvuDispatchAdapterSpec evu.EvuFpQuantElaborationSpec -- -z prefetch -z 'selected arithmetic' -z 'reject retired' -z elaborate -z 'retain elaboration'
# Focused rerun: 6/6 PASS. First-run mapping/arithmetic/stream/flush cases pass.
/tmp/xsai-flash-attention-ref-test ../NEMU/build/riscv64-nemu-interpreter-so
/tmp/xsai-evu-matrix-ref-test ../NEMU/build/riscv64-nemu-interpreter-so
```

Native executable compile commands and server guest syntax/build instructions
are in software/evu/docs/FLASH_ATTENTION.md. From NEMU:

```sh
make -j8 git_commit=
make -C tools/evu-ref-test
./tools/evu-ref-test/evu_compute_ref_test ./build/riscv64-nemu-interpreter-so
./tools/evu-ref-test/evu_fp_quant_ref_test ./build/riscv64-nemu-interpreter-so
```

Logs: NEMU/build/evu-quant-pipeline-{compute,fp}.log and
XSAI_external_vector/build/evu-quant-pipeline-{attention,matrix}.log.
Expected negative-case diagnostics in the native logs are followed by PASS
and zero exit status. Interpreter output is in Mill's evuCheck test results.
