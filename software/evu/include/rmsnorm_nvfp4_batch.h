#ifndef XSAI_RMSNORM_NVFP4_BATCH_H
#define XSAI_RMSNORM_NVFP4_BATCH_H

#define RN_QUANT_INITIALIZE() do { \
  (void)xv_setvli(16, 0x10); \
  XV_LOAD_E0(25, &quant_constants[1]); \
  XV_LOAD_E0(26, &quant_constants[2]); \
  XV_LOAD_E0(27, &quant_constants[3]); \
  XV_LOAD_E0(28, &constants[0]); \
  XV_LOAD_E0(29, &quant_integers[0]); \
  XV_LOAD_E0(30, &quant_integers[1]); \
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
  XV_FREDUCE_MAX(20, 28, 18, 2); \
  XV_FREDUCE_MAX(21, 28, 19, 2); \
  XV_SLIDEUP(22, index, 20, 2); \
  XV_SLIDEUP(22, (index) + 1, 21, 2); \
} while (0)

#define RN_BATCH_PACK_PAIR(source, block, index, next_index) do { \
  (void)xv_setvli(16, 0x10); \
  XV_SLIDEDOWN(16, 16 * (block), source, 2); \
  XV_SLIDEDOWN(17, 16 * ((block) + 1), source, 2); \
  XV_GATHER_IMM(22, index, 31, 2); \
  XV_GATHER_IMM(23, next_index, 31, 2); \
  XV_FMUL(18, 22, 16, 2); \
  XV_FMUL(19, 23, 17, 2); \
  XV_GATHER_IMM(22, index, 21, 2); \
  XV_GATHER_IMM(23, next_index, 21, 2); \
  XV_BINARY(0x03, 18, 22, 18, 2); \
  XV_BINARY(0x03, 19, 23, 19, 2); \
  XV_CVT_MXFP4_F32(22, 30, 18); \
  XV_CVT_MXFP4_F32(23, 30, 19); \
  (void)xv_setvli(8 * ((index) + 1), 0); \
  XV_SLIDEUP(20, 8 * (index), 22, 0); \
  (void)xv_setvli(8 * ((index) + 2), 0); \
  XV_SLIDEUP(20, 8 * ((index) + 1), 23, 0); \
} while (0)

#define RN_BATCH_STORE_SCALES_0(batch)
#define RN_BATCH_STORE_SCALES_1(batch) XV_STORE(24, &scales.data[16 * ((batch) - 3)], 0)
#define RN_BATCH_STORE_SCALES(ready, batch) RN_BATCH_STORE_SCALES_##ready(batch)

#define RN_QUANT_BATCH(batch, source0, source1, scales_ready) do { \
  (void)xv_setvli(16, 0x10); \
  RN_BATCH_MAX_PAIR(source0, 0, 0); \
  RN_BATCH_MAX_PAIR(source0, 2, 2); \
  RN_BATCH_MAX_PAIR(source0, 4, 4); \
  RN_BATCH_MAX_PAIR(source0, 6, 6); \
  RN_BATCH_MAX_PAIR(source1, 0, 8); \
  RN_BATCH_MAX_PAIR(source1, 2, 10); \
  RN_BATCH_MAX_PAIR(source1, 4, 12); \
  RN_BATCH_MAX_PAIR(source1, 6, 14); \
  XV_LOAD_E0(18, &quant_constants[0]); \
  XV_BROADCAST(18, 18, 2); \
  XV_FDIV(20, 18, 22, 2); \
  XV_BINARY(0x25, 21, 29, 22, 2); \
  XV_BINARY(0x02, 21, 21, 28, 2); \
  XV_BINARY(0x03, 19, 27, 21, 2); \
  XV_FDIV(20, 25, 20, 2); \
  XV_FMAX(20, 19, 20, 2); \
  XV_CVT_E4M3_F32(23, 20); \
  XV_CVT_F32_E4M3(31, 23); \
  XV_FMAX(31, 27, 31, 2); \
  XV_FMUL(31, 25, 31, 2); \
  XV_FDIV(31, 31, 26, 2); \
  (void)xv_setvli(16 * (((batch) & 3) + 1), 0); \
  XV_SLIDEUP(24, 16 * ((batch) & 3), 23, 0); \
  RN_BATCH_STORE_SCALES(scales_ready, batch); \
  RN_BATCH_PACK_PAIR(source0, 0, 0, 1); \
  RN_BATCH_PACK_PAIR(source0, 2, 2, 3); \
  RN_BATCH_PACK_PAIR(source0, 4, 4, 5); \
  RN_BATCH_PACK_PAIR(source0, 6, 6, 7); \
  RN_BATCH_PACK_PAIR(source1, 0, 8, 9); \
  RN_BATCH_PACK_PAIR(source1, 2, 10, 11); \
  RN_BATCH_PACK_PAIR(source1, 4, 12, 13); \
  RN_BATCH_PACK_PAIR(source1, 6, 14, 15); \
  XV_STORE(20, &packed.data[128 * (batch)], 0); \
} while (0)

#define RN_QUANT_BATCHES() do { \
  RN_QUANT_BATCH(0, 0, 1, 0); \
  RN_QUANT_BATCH(1, 2, 3, 0); \
  RN_QUANT_BATCH(2, 4, 5, 0); \
  RN_QUANT_BATCH(3, 6, 7, 1); \
  RN_QUANT_BATCH(4, 8, 9, 0); \
  RN_QUANT_BATCH(5, 10, 11, 0); \
  RN_QUANT_BATCH(6, 12, 13, 0); \
  RN_QUANT_BATCH(7, 14, 15, 1); \
} while (0)

#endif
