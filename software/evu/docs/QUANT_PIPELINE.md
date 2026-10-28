# Compact FP quantization and matrix dequantization

The dedicated CUTE paths now use small elastic pipelines. Ordinary EVU/Saturn
arithmetic is unchanged. Rebuild EMU and the NEMU reference on the server;
local validation uses Scala compilation, in-memory elaboration, the FIRRTL
interpreter and native reference tests. No local Verilog, EMU or guest binary
is required.

## Quantization: FP32 probabilities into packed CUTE INT8

The existing `XV_FPQUANT_TO_CUTE`, custom0 funct7=0x50, keeps its encoding,
target layout and numerical contract. Two probability slots and two inverse
scale slots collect the operands independently, including same-bank conflicts.
Reading subsequent groups can continue while earlier groups compute or wait
for CUTE. The three elastic stages perform:

1. FP32 RNE multiplication.
2. Separately rounded FP32 RNE addition of 0.5.
3. minNum/clamp to [0,127], unsigned RTZ conversion and eight-byte packing.

Each stage retains its lane mask, chunk number and accumulated IEEE flags
under backpressure. Only active lanes contribute flags. Completion follows
the last **accepted** CUTE write and includes that write's flags. Flush clears
all operand slots, pipeline valids and pending completion state. VL=0 issues
neither a VRF read nor a CUTE write.

The arithmetic pipeline has an initiation interval of one eight-FP32 group.
Actual command throughput remains limited by Saturn's synchronous read
collector, bank conflicts and CUTE readiness. No new VRF SRAM ports are added.
Quantization processes 256 input bits into 64 output bits per group; this is
not 256 bits of packed INT8 output per cycle.

## Dequantization: CUTE INT32 results into FP32 HBL2 output

The launch interface is unchanged. The first elastic stage converts INT32 to
FP32 RNE and multiplies by scaleA, rounding again. The second multiplies by
scaleB with another FP32 RNE rounding. Two output slots decouple short stalls.
Metadata and flags travel with the corresponding result throughout.

Only one eight-value chunk of scaleA and one of scaleB are cached. Keys include
both the VRF register and chunk index. A miss collects the operands separately;
ownership persists until collection completes even if another command arrives.
An accepted command that writes any VRF register invalidates the cache, as
does flush. In-flight beats protect their source registers through an eight-
entry metadata scoreboard until their outputs are accepted. Reuse of cached
data therefore cannot bypass an intervening source-register write.

With both scale chunks cached and the consumer ready, the pipeline accepts
and emits one 256-bit group per cycle after filling. Changing a chunk causes
a miss. The HBL2 writer still permits one matrix store awaiting acknowledgment;
partial-line writes still use the existing RMW adapter. Sustained memory
bandwidth and whole-Attention speedup must be measured separately on EMU.

## Storage and arithmetic resources

Default DLEN=256 datapath payload capacity, including retained queues:

| Storage | Bytes |
| --- | ---: |
| Two slots for each of the two quantization operands | 128 |
| Two rounded quant stages (8x33 bits each) and packed output stage | 74 |
| Current A/B scale chunks | 64 |
| Two INT32 matrix input slots | 64 |
| Dequant stage one (256-bit result + 32-bit B scale) and stage two | 68 |
| Two FP32 matrix output slots | 64 |
| Total datapath payload | **462** |

This is total payload capacity in these paths, not 462 newly added bytes.
Lane masks, chunk tags, matrix coordinates, flags, queue control and hazard
masks add roughly 130 bytes. Existing command/completion records and shared
VRF/HBL2 state are not included. This is a source-level bit count, not a
synthesis area or timing report. The default FIRRTL interpreter circuit contains
5111 state bits (registers and queue payloads) in the entire special sequencer,
including its existing full command/completion records. The backend's matrix
hazard scoreboard contributes another 266 bits, for about 672 bytes combined;
shared VRF/HBL2 state is additional. Unused fields can be removed by synthesis.
No full-vector scale cache, line merge buffer
or extra matrix memory transaction slot is introduced.

FP quantization keeps eight multiply and eight add lanes. Matrix dequantization
keeps eight INT32 converters and two arrays of eight FP multipliers; the old
unused scale-fold arithmetic and fixed-point multiply/shift/clamp network are
removed. Pipelining adds registers rather than another copy of those arrays.

## Retired fixed-point interface

Fixed-point `QuantToCute` (funct7=0x4c) and its `SETCFG` instruction (0x4e) are
retired. RTL and NEMU reject them as illegal instructions at every SEW,
including VL=0. The descriptor decoder, local descriptor register array,
fixed-point arithmetic and legacy 32-bit-word CUTE write interpretation are
removed. No FP quantization fallback uses those instructions.

The eight `eqdesc` CSR addresses/storage and their existing checkpoint layout
remain as reserved context slots for ABI compatibility. They have no execution
consumer and configuring them does not configure either remaining arithmetic
path. Software configures FP quantization with `evcfg`, its FP32 inverse-scale
VRF operand and scalar CUTE target; matrix dequantization uses launch's A/B
scale register operands. No new CSR is introduced.

The arithmetic/FP64 accuracy checks are described in [FLASH_ATTENTION.md](FLASH_ATTENTION.md).
