#ifndef XSAI_RMSNORM_NVFP4_BATCH_H
#define XSAI_RMSNORM_NVFP4_BATCH_H

#define RN_QUANT_INITIALIZE() do { \
  (void)xv_setvli(16, 0x10); \
  XV_LOAD_E0(23, &quant_constants[0]); \
  XV_LOAD_E0(25, &quant_constants[1]); \
  XV_LOAD_E0(26, &quant_constants[2]); \
  XV_LOAD_E0(27, &quant_constants[3]); \
  XV_LOAD_E0(28, &constants[0]); \
  XV_LOAD_E0(29, &quant_integers[0]); \
  XV_LOAD_E0(30, &quant_integers[1]); \
  XV_BROADCAST(23, 23, 2); \
  XV_BROADCAST(25, 25, 2); \
  XV_BROADCAST(26, 26, 2); \
  XV_BROADCAST(27, 27, 2); \
  XV_BROADCAST(28, 28, 2); \
  XV_BROADCAST(29, 29, 2); \
} while (0)

#define RN_BATCH_MAX_PAIR(source, block, index) do { \
  XV_SLIDEDOWN(16, 16 * (block), source, 2); \
  XV_SLIDEDOWN(17, 16 * ((block) + 1), source, 2); \
  XV_FABS(18, 16, 2); \
  XV_FABS(19, 17, 2); \
  XV_FREDUCE_MAX(16, 28, 18, 2); \
  XV_FREDUCE_MAX(17, 28, 19, 2); \
  XV_SLIDEUP(22, index, 16, 2); \
  XV_SLIDEUP(22, (index) + 1, 17, 2); \
} while (0)

#define RN_BATCH_PACK_PAIR(source, block, index, next_index, inverse, mask) do { \
  XV_SLIDEDOWN(16, 16 * (block), source, 2); \
  XV_SLIDEDOWN(17, 16 * ((block) + 1), source, 2); \
  XV_GATHER_IMM(18, index, inverse, 2); \
  XV_GATHER_IMM(19, next_index, inverse, 2); \
  XV_FMUL(16, 18, 16, 2); \
  XV_FMUL(17, 19, 17, 2); \
  XV_GATHER_IMM(18, index, mask, 2); \
  XV_GATHER_IMM(19, next_index, mask, 2); \
  XV_BINARY(0x03, 16, 18, 16, 2); \
  XV_BINARY(0x03, 17, 19, 17, 2); \
  XV_CVT_MXFP4_F32(18, 30, 16); \
  XV_CVT_MXFP4_F32(19, 30, 17); \
  XV_SLIDEUP(20, 2 * ((index) & 7), 18, 2); \
  XV_SLIDEUP(20, 2 * ((next_index) & 7), 19, 2); \
} while (0)

#define RN_BATCH_STORE_SCALES_0(batch)
#define RN_BATCH_STORE_SCALES_1(batch) XV_STORE(24, &scales.data[16 * ((batch) - 3)], 2)
#define RN_BATCH_STORE_SCALES(ready, batch) RN_BATCH_STORE_SCALES_##ready(batch)

#define RN_BATCH_SCALE_BEGIN(inverse, mask) do { \
  XV_BINARY(0x25, mask, 29, 22, 2); \
  XV_BINARY(0x02, mask, mask, 28, 2); \
  XV_FDIV(inverse, 23, 22, 2); \
} while (0)

#define RN_BATCH_SCALE_ALPHA(inverse) XV_FDIV(inverse, 25, inverse, 2)

#define RN_BATCH_SCALE_ENCODE(batch, inverse, mask, scales_ready) do { \
  XV_BINARY(0x03, 18, 27, mask, 2); \
  XV_FMAX(inverse, 18, inverse, 2); \
  XV_CVT_E4M3_F32(16, inverse); \
  XV_CVT_F32_E4M3(inverse, 16); \
  XV_SLIDEUP(24, 4 * ((batch) & 3), 16, 2); \
  RN_BATCH_STORE_SCALES(scales_ready, batch); \
} while (0)

#define RN_BATCH_SCALE_RECIPROCAL(inverse) do { \
  XV_FMAX(inverse, 27, inverse, 2); \
  XV_FMUL(inverse, 25, inverse, 2); \
  XV_FDIV(inverse, inverse, 26, 2); \
} while (0)

#define RN_BATCH_STORE_PACKED(batch, half) \
  XV_STORE(20, &packed.data[64 * (2 * (batch) + (half))], 2)

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

#define RN_BATCH_NO_MAX_PAIR(source, block, index)

#define RN_BATCH_OVERLAP(batch, inverse, mask, previous_inverse, previous_mask, \
                         previous0, previous1, max_pair, next0, next1, scales_ready) do { \
  RN_BATCH_SCALE_BEGIN(inverse, mask); \
  max_pair(next0, 0, 0); \
  RN_BATCH_PACK_PAIR(previous0, 0, 0, 1, previous_inverse, previous_mask); \
  RN_BATCH_SCALE_ALPHA(inverse); \
  max_pair(next0, 2, 2); \
  RN_BATCH_PACK_PAIR(previous0, 2, 2, 3, previous_inverse, previous_mask); \
  RN_BATCH_SCALE_ENCODE(batch, inverse, mask, scales_ready); \
  max_pair(next0, 4, 4); \
  RN_BATCH_PACK_PAIR(previous0, 4, 4, 5, previous_inverse, previous_mask); \
  RN_BATCH_SCALE_RECIPROCAL(inverse); \
  max_pair(next0, 6, 6); \
  RN_BATCH_PACK_PAIR(previous0, 6, 6, 7, previous_inverse, previous_mask); \
  RN_BATCH_STORE_PACKED((batch) - 1, 0); \
  max_pair(next1, 0, 8); \
  RN_BATCH_PACK_PAIR(previous1, 0, 8, 9, previous_inverse, previous_mask); \
  max_pair(next1, 2, 10); \
  RN_BATCH_PACK_PAIR(previous1, 2, 10, 11, previous_inverse, previous_mask); \
  max_pair(next1, 4, 12); \
  RN_BATCH_PACK_PAIR(previous1, 4, 12, 13, previous_inverse, previous_mask); \
  max_pair(next1, 6, 14); \
  RN_BATCH_PACK_PAIR(previous1, 6, 14, 15, previous_inverse, previous_mask); \
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
  RN_BATCH_PRIME(0, 31, 21, 2, 3); \
  RN_BATCH_PACK(0, 0, 1, 31, 21); \
  RN_BATCH_PRIME(1, 0, 1, 4, 5); \
  RN_BATCH_OVERLAP(2, 31, 21, 0, 1, 2, 3, RN_BATCH_MAX_PAIR, 6, 7, 0); \
  RN_BATCH_OVERLAP(3, 0, 1, 31, 21, 4, 5, RN_BATCH_MAX_PAIR, 8, 9, 1); \
  RN_BATCH_OVERLAP(4, 31, 21, 0, 1, 6, 7, RN_BATCH_MAX_PAIR, 10, 11, 0); \
  RN_BATCH_OVERLAP(5, 0, 1, 31, 21, 8, 9, RN_BATCH_MAX_PAIR, 12, 13, 0); \
  RN_BATCH_OVERLAP(6, 31, 21, 0, 1, 10, 11, RN_BATCH_MAX_PAIR, 14, 15, 0); \
  RN_BATCH_OVERLAP(7, 0, 1, 31, 21, 12, 13, RN_BATCH_NO_MAX_PAIR, 0, 0, 1); \
  RN_BATCH_PACK(7, 14, 15, 0, 1); \
} while (0)

#endif
