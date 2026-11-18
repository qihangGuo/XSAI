#ifndef XSAI_RMSNORM_NVFP4_BATCH_H
#define XSAI_RMSNORM_NVFP4_BATCH_H

#ifndef EVU_RMS_QUANT_RENAME
#define EVU_RMS_QUANT_RENAME 1
#endif
#if EVU_RMS_QUANT_RENAME != 0 && EVU_RMS_QUANT_RENAME != 1
#error "EVU_RMS_QUANT_RENAME must be 0 or 1"
#endif

#ifndef EVU_RMS_QUANT_BANKED
#define EVU_RMS_QUANT_BANKED 1
#endif
#if EVU_RMS_QUANT_BANKED != 0 && EVU_RMS_QUANT_BANKED != 1
#error "EVU_RMS_QUANT_BANKED must be 0 or 1"
#endif

#if EVU_RMS_QUANT_BANKED
/* First-group banks: data=1/0, work=3/2, factor=2, zero=0, inverse=0.
 * Together with amax scratch v2/v3 (banks 2/3), binary sources stay apart. */
#define RN_QUANT_SIX 31
#define RN_QUANT_ONE 29
#define RN_QUANT_MIN_SCALE 28
#define RN_QUANT_INT_ONE 26
#define RN_QUANT_ZERO 17
#define RN_QUANT_FACTOR 22
#define RN_QUANT_SECOND 27
#define RN_QUANT_PACKED 30
#define RN_QUANT_MAXIMA 23
#define RN_QUANT_INVERSE 20
#else
#if EVU_RMS_QUANT_RENAME
#define RN_QUANT_SIX 27
#define RN_QUANT_ONE 29
#define RN_QUANT_MIN_SCALE 23
#define RN_QUANT_INT_ONE 26
#else
#define RN_QUANT_SIX 23
#define RN_QUANT_ONE 26
#define RN_QUANT_MIN_SCALE 27
#define RN_QUANT_INT_ONE 29
#endif
#define RN_QUANT_ZERO 28
#define RN_QUANT_FACTOR 30
#define RN_QUANT_SECOND 17
#define RN_QUANT_PACKED 20
#define RN_QUANT_MAXIMA 22
#define RN_QUANT_INVERSE 31
#endif

#define RN_QUANT_INITIALIZE() do { \
  (void)xv_setvli(16, 0x10); \
  XV_LOAD_E0(RN_QUANT_SIX, &quant_constants[0]); \
  XV_LOAD_E0(25, &quant_constants[1]); \
  XV_LOAD_E0(RN_QUANT_ONE, &quant_constants[2]); \
  XV_LOAD_E0(RN_QUANT_MIN_SCALE, &quant_constants[3]); \
  XV_LOAD_E0(RN_QUANT_ZERO, &constants[0]); \
  XV_LOAD_E0(RN_QUANT_INT_ONE, &quant_integers[0]); \
  XV_LOAD_E0(RN_QUANT_FACTOR, &quant_integers[1]); \
  XV_BROADCAST(RN_QUANT_SIX, RN_QUANT_SIX, 2); \
  XV_BROADCAST(25, 25, 2); \
  XV_BROADCAST(RN_QUANT_ONE, RN_QUANT_ONE, 2); \
  XV_BROADCAST(RN_QUANT_MIN_SCALE, RN_QUANT_MIN_SCALE, 2); \
  XV_BROADCAST(RN_QUANT_ZERO, RN_QUANT_ZERO, 2); \
  XV_BROADCAST(RN_QUANT_INT_ONE, RN_QUANT_INT_ONE, 2); \
} while (0)

/* The fused-only entry may consume block zero directly from the input. */
#ifndef RN_BATCH_EXTRACT_FIRST
#define RN_BATCH_EXTRACT_FIRST(first, block, source) XV_SLIDEDOWN(first, 16 * (block), source, 2)
#endif
#ifndef RN_BATCH_FIRST_REG
#define RN_BATCH_FIRST_REG(first, block, source) first
#endif

#ifndef EVU_RMS_QUANT_DIRECT_MAXIMA
#define EVU_RMS_QUANT_DIRECT_MAXIMA 0
#endif
#ifndef EVU_RMS_QUANT_SCALAR_FIRST
#define EVU_RMS_QUANT_SCALAR_FIRST 0
#endif
#if (EVU_RMS_QUANT_DIRECT_MAXIMA != 0 && EVU_RMS_QUANT_DIRECT_MAXIMA != 1) || \
    (EVU_RMS_QUANT_SCALAR_FIRST != 0 && EVU_RMS_QUANT_SCALAR_FIRST != 1)
#error "EVU_RMS_QUANT_DIRECT_MAXIMA and EVU_RMS_QUANT_SCALAR_FIRST must be 0 or 1"
#endif

/* Pair indices are literals: select E0 without adding scalar control flow. */
#define RN_BATCH_ZERO_0(zero, other) zero
#define RN_BATCH_ZERO_2(zero, other) other
#define RN_BATCH_ZERO_4(zero, other) other
#define RN_BATCH_ZERO_6(zero, other) other
#define RN_BATCH_ZERO_8(zero, other) other
#define RN_BATCH_ZERO_10(zero, other) other
#define RN_BATCH_ZERO_12(zero, other) other
#define RN_BATCH_ZERO_14(zero, other) other
#define RN_BATCH_ZERO_IMPL(index, zero, other) RN_BATCH_ZERO_##index(zero, other)
#define RN_BATCH_ZERO(index, zero, other) RN_BATCH_ZERO_IMPL(index, zero, other)

#if EVU_RMS_QUANT_DIRECT_MAXIMA
#define RN_BATCH_MAX_DEST(index, first) RN_BATCH_ZERO(index, RN_QUANT_MAXIMA, first)
#define RN_BATCH_MAX_INSERT(index, first) \
  RN_BATCH_ZERO(index, (void)0, XV_SLIDEUP(RN_QUANT_MAXIMA, index, first, 2))
#else
#define RN_BATCH_MAX_DEST(index, first) first
#define RN_BATCH_MAX_INSERT(index, first) XV_SLIDEUP(RN_QUANT_MAXIMA, index, first, 2)
#endif

#if EVU_RMS_QUANT_SCALAR_FIRST
#define RN_BATCH_PACK_GATHER(index, inverse) \
  RN_BATCH_ZERO(index, (void)0, XV_GATHER_IMM(18, index, inverse, 2))
#define RN_BATCH_PACK_MUL(index, source, inverse) \
  RN_BATCH_ZERO(index, XV_FMUL_SCALAR(16, inverse, source), XV_FMUL(16, 18, source, 2))
#else
#define RN_BATCH_PACK_GATHER(index, inverse) XV_GATHER_IMM(18, index, inverse, 2)
#define RN_BATCH_PACK_MUL(index, source, inverse) XV_FMUL(16, 18, source, 2)
#endif

#define RN_BATCH_MAX_PAIR_REGS(source, block, index, first, second, abs_first, abs_second) do { \
  RN_BATCH_EXTRACT_FIRST(first, block, source); \
  XV_SLIDEDOWN(second, 16 * ((block) + 1), source, 2); \
  XV_FABS(abs_first, RN_BATCH_FIRST_REG(first, block, source), 2); \
  XV_FABS(abs_second, second, 2); \
  XV_FREDUCE_MAX(RN_BATCH_MAX_DEST(index, first), RN_QUANT_ZERO, abs_first, 2); \
  XV_FREDUCE_MAX(second, RN_QUANT_ZERO, abs_second, 2); \
  RN_BATCH_MAX_INSERT(index, first); \
  XV_SLIDEUP(RN_QUANT_MAXIMA, (index) + 1, second, 2); \
} while (0)

#define RN_BATCH_MAX_PAIR(source, block, index) \
  RN_BATCH_MAX_PAIR_REGS(source, block, index, 16, RN_QUANT_SECOND, 18, 19)
#if EVU_RMS_QUANT_RENAME
#define RN_BATCH_STEADY_MAX_PAIR(source, block, index) \
  RN_BATCH_MAX_PAIR_REGS(source, block, index, 2, 3, 2, 3)
#else
#define RN_BATCH_STEADY_MAX_PAIR(source, block, index) RN_BATCH_MAX_PAIR(source, block, index)
#endif

#define RN_BATCH_PACK_PAIR(source, block, index, next_index, inverse, mask) do { \
  RN_BATCH_EXTRACT_FIRST(16, block, source); \
  XV_SLIDEDOWN(RN_QUANT_SECOND, 16 * ((block) + 1), source, 2); \
  RN_BATCH_PACK_GATHER(index, inverse); \
  XV_GATHER_IMM(19, next_index, inverse, 2); \
  RN_BATCH_PACK_MUL(index, RN_BATCH_FIRST_REG(16, block, source), inverse); \
  XV_FMUL(RN_QUANT_SECOND, 19, RN_QUANT_SECOND, 2); \
  XV_GATHER_IMM(18, index, mask, 2); \
  XV_GATHER_IMM(19, next_index, mask, 2); \
  XV_BINARY(0x03, 16, 18, 16, 2); \
  XV_BINARY(0x03, RN_QUANT_SECOND, 19, RN_QUANT_SECOND, 2); \
  XV_CVT_MXFP4_F32(18, RN_QUANT_FACTOR, 16); \
  XV_CVT_MXFP4_F32(19, RN_QUANT_FACTOR, RN_QUANT_SECOND); \
  XV_SLIDEUP(RN_QUANT_PACKED, 2 * ((index) & 7), 18, 2); \
  XV_SLIDEUP(RN_QUANT_PACKED, 2 * ((next_index) & 7), 19, 2); \
} while (0)

#define RN_BATCH_STORE_SCALES_0(batch)
#define RN_BATCH_STORE_SCALES_1(batch) XV_STORE(24, &scales.data[16 * ((batch) - 3)], 2)
#define RN_BATCH_STORE_SCALES(ready, batch) RN_BATCH_STORE_SCALES_##ready(batch)

#define RN_BATCH_SCALE_BEGIN(inverse, mask) do { \
  XV_BINARY(0x25, mask, RN_QUANT_INT_ONE, RN_QUANT_MAXIMA, 2); \
  XV_BINARY(0x02, mask, mask, RN_QUANT_ZERO, 2); \
  XV_FDIV(inverse, RN_QUANT_SIX, RN_QUANT_MAXIMA, 2); \
} while (0)

#define RN_BATCH_SCALE_ALPHA(inverse) XV_FDIV(inverse, 25, inverse, 2)

#define RN_BATCH_SCALE_ENCODE(batch, inverse, mask, scales_ready) do { \
  XV_BINARY(0x03, 18, RN_QUANT_MIN_SCALE, mask, 2); \
  XV_FMAX(inverse, 18, inverse, 2); \
  XV_CVT_E4M3_F32(16, inverse); \
  XV_CVT_F32_E4M3(inverse, 16); \
  XV_SLIDEUP(24, 4 * ((batch) & 3), 16, 2); \
  RN_BATCH_STORE_SCALES(scales_ready, batch); \
} while (0)

#define RN_BATCH_SCALE_RECIPROCAL(inverse) do { \
  XV_FMAX(inverse, RN_QUANT_MIN_SCALE, inverse, 2); \
  XV_FMUL(inverse, 25, inverse, 2); \
  XV_FDIV(inverse, inverse, RN_QUANT_ONE, 2); \
} while (0)

#define RN_BATCH_STORE_PACKED(batch, half) \
  XV_STORE(RN_QUANT_PACKED, &packed.data[64 * (2 * (batch) + (half))], 2)

#define RN_BATCH_PACK(batch, source0, source1, inverse, mask) do { \
  RN_BATCH_PACK_PAIR(source0, 0, 0, 1, inverse, mask); \
  RN_BATCH_PACK_PAIR(source0, 2, 2, 3, inverse, mask); \
  RN_BATCH_PACK_PAIR(source0, 4, 4, 5, inverse, mask); \
  RN_BATCH_PACK_PAIR(source0, 6, 6, 7, inverse, mask); \
  RN_BATCH_STORE_PACKED(batch, 0); \
  RN_BATCH_PACK_PAIR(source1, 0, 8, 9, inverse, mask); \
  RN_BATCH_PACK_PAIR(source1, 2, 10, 11, inverse, mask); \
  RN_BATCH_PACK_PAIR(source1, 4, 12, 13, inverse, mask); \
  RN_BATCH_PACK_PAIR(source1, 6, 14, 15, inverse, mask); \
  RN_BATCH_STORE_PACKED(batch, 1); \
} while (0)

#define RN_BATCH_PRIME(batch, inverse, mask, next0, next1) do { \
  RN_BATCH_SCALE_BEGIN(inverse, mask); \
  RN_BATCH_MAX_PAIR(next0, 0, 0); \
  RN_BATCH_MAX_PAIR(next0, 2, 2); \
  RN_BATCH_SCALE_ALPHA(inverse); \
  RN_BATCH_MAX_PAIR(next0, 4, 4); \
  RN_BATCH_MAX_PAIR(next0, 6, 6); \
  RN_BATCH_SCALE_ENCODE(batch, inverse, mask, 0); \
  RN_BATCH_SCALE_RECIPROCAL(inverse); \
  RN_BATCH_MAX_PAIR(next1, 0, 8); \
  RN_BATCH_MAX_PAIR(next1, 2, 10); \
  RN_BATCH_MAX_PAIR(next1, 4, 12); \
  RN_BATCH_MAX_PAIR(next1, 6, 14); \
} while (0)

#define RN_BATCH_MAX_PACK_PAIR(next_source, source, block, index, next_index, inverse, mask) do { \
  RN_BATCH_MAX_PAIR(next_source, block, index); \
  RN_BATCH_PACK_PAIR(source, block, index, next_index, inverse, mask); \
} while (0)

#if EVU_RMS_QUANT_BANKED && EVU_RMS_QUANT_RENAME
/* v2/v3 are dead inputs here. Interleave independent amax and packing
 * operations without letting either path overwrite the other's operands. */
#define RN_BATCH_STEADY_PAIR(next_source, source, block, index, next_index, inverse, mask) do { \
  RN_BATCH_EXTRACT_FIRST(2, block, next_source); \
  RN_BATCH_EXTRACT_FIRST(16, block, source); \
  XV_SLIDEDOWN(3, 16 * ((block) + 1), next_source, 2); \
  XV_SLIDEDOWN(RN_QUANT_SECOND, 16 * ((block) + 1), source, 2); \
  XV_FABS(2, RN_BATCH_FIRST_REG(2, block, next_source), 2); \
  RN_BATCH_PACK_GATHER(index, inverse); \
  XV_FABS(3, 3, 2); \
  XV_GATHER_IMM(19, next_index, inverse, 2); \
  RN_BATCH_PACK_MUL(index, RN_BATCH_FIRST_REG(16, block, source), inverse); \
  XV_FREDUCE_MAX(RN_BATCH_MAX_DEST(index, 2), RN_QUANT_ZERO, 2, 2); \
  XV_FMUL(RN_QUANT_SECOND, 19, RN_QUANT_SECOND, 2); \
  XV_FREDUCE_MAX(3, RN_QUANT_ZERO, 3, 2); \
  XV_GATHER_IMM(18, index, mask, 2); \
  RN_BATCH_MAX_INSERT(index, 2); \
  XV_GATHER_IMM(19, next_index, mask, 2); \
  XV_SLIDEUP(RN_QUANT_MAXIMA, (index) + 1, 3, 2); \
  XV_BINARY(0x03, 16, 18, 16, 2); \
  XV_BINARY(0x03, RN_QUANT_SECOND, 19, RN_QUANT_SECOND, 2); \
  XV_CVT_MXFP4_F32(18, RN_QUANT_FACTOR, 16); \
  XV_CVT_MXFP4_F32(19, RN_QUANT_FACTOR, RN_QUANT_SECOND); \
  XV_SLIDEUP(RN_QUANT_PACKED, 2 * ((index) & 7), 18, 2); \
  XV_SLIDEUP(RN_QUANT_PACKED, 2 * ((next_index) & 7), 19, 2); \
} while (0)
#else
#define RN_BATCH_STEADY_PAIR(next_source, source, block, index, next_index, inverse, mask) do { \
  RN_BATCH_STEADY_MAX_PAIR(next_source, block, index); \
  RN_BATCH_PACK_PAIR(source, block, index, next_index, inverse, mask); \
} while (0)
#endif

#define RN_BATCH_PACK_ONLY_PAIR(next_source, source, block, index, next_index, inverse, mask) \
  RN_BATCH_PACK_PAIR(source, block, index, next_index, inverse, mask)

#define RN_BATCH_OVERLAP(batch, inverse, mask, previous_inverse, previous_mask, \
                         previous0, previous1, pair, next0, next1, scales_ready) do { \
  RN_BATCH_SCALE_BEGIN(inverse, mask); \
  pair(next0, previous0, 0, 0, 1, previous_inverse, previous_mask); \
  RN_BATCH_SCALE_ALPHA(inverse); \
  pair(next0, previous0, 2, 2, 3, previous_inverse, previous_mask); \
  RN_BATCH_SCALE_ENCODE(batch, inverse, mask, scales_ready); \
  pair(next0, previous0, 4, 4, 5, previous_inverse, previous_mask); \
  RN_BATCH_SCALE_RECIPROCAL(inverse); \
  pair(next0, previous0, 6, 6, 7, previous_inverse, previous_mask); \
  RN_BATCH_STORE_PACKED((batch) - 1, 0); \
  pair(next1, previous1, 0, 8, 9, previous_inverse, previous_mask); \
  pair(next1, previous1, 2, 10, 11, previous_inverse, previous_mask); \
  pair(next1, previous1, 4, 12, 13, previous_inverse, previous_mask); \
  pair(next1, previous1, 6, 14, 15, previous_inverse, previous_mask); \
  RN_BATCH_STORE_PACKED((batch) - 1, 1); \
} while (0)

#define RN_QUANT_BATCHES() do { \
  RN_BATCH_MAX_PAIR(0, 0, 0); \
  RN_BATCH_MAX_PAIR(0, 2, 2); \
  RN_BATCH_MAX_PAIR(0, 4, 4); \
  RN_BATCH_MAX_PAIR(0, 6, 6); \
  RN_BATCH_MAX_PAIR(1, 0, 8); \
  RN_BATCH_MAX_PAIR(1, 2, 10); \
  RN_BATCH_MAX_PAIR(1, 4, 12); \
  RN_BATCH_MAX_PAIR(1, 6, 14); \
  RN_BATCH_PRIME(0, RN_QUANT_INVERSE, 21, 2, 3); \
  RN_BATCH_PACK(0, 0, 1, RN_QUANT_INVERSE, 21); \
  RN_BATCH_PRIME(1, 0, 1, 4, 5); \
  RN_BATCH_OVERLAP(2, RN_QUANT_INVERSE, 21, 0, 1, 2, 3, RN_BATCH_MAX_PACK_PAIR, 6, 7, 0); \
  RN_BATCH_OVERLAP(3, 0, 1, RN_QUANT_INVERSE, 21, 4, 5, RN_BATCH_STEADY_PAIR, 8, 9, 1); \
  RN_BATCH_OVERLAP(4, RN_QUANT_INVERSE, 21, 0, 1, 6, 7, RN_BATCH_STEADY_PAIR, 10, 11, 0); \
  RN_BATCH_OVERLAP(5, 0, 1, RN_QUANT_INVERSE, 21, 8, 9, RN_BATCH_STEADY_PAIR, 12, 13, 0); \
  RN_BATCH_OVERLAP(6, RN_QUANT_INVERSE, 21, 0, 1, 10, 11, RN_BATCH_STEADY_PAIR, 14, 15, 0); \
  RN_BATCH_OVERLAP(7, 0, 1, RN_QUANT_INVERSE, 21, 12, 13, RN_BATCH_PACK_ONLY_PAIR, 0, 0, 1); \
  RN_BATCH_PACK(7, 14, 15, 0, 1); \
} while (0)

#endif
