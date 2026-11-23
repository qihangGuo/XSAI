/* Reuse the checked test's storage, ABI helpers and quantization primitives. */
#define EVU_RMS_TILE_KERNELS_ONLY 1
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused"
#include "rmsnorm_nvfp4_tile128x64_evu_test.c"
#pragma GCC diagnostic pop
#if !EVU_RMS_QUANT_BANKED
#error "The optimized tile schedule requires the bank-aware register layout"
#endif

/* Keep each pair of squares and sums together at VL=64. The reduction
 * destinations may outlive the square scratch registers for the next pair. */
#define PERF_SUM_FOUR(tile, r0, r1, r2, r3, s0, s1, s2, s3) do { \
  XV_LOAD(r0, &inputs[(16 * (tile) + (r0)) * COLS], 2); \
  XV_LOAD(r1, &inputs[(16 * (tile) + (r1)) * COLS], 2); \
  XV_LOAD(r2, &inputs[(16 * (tile) + (r2)) * COLS], 2); \
  XV_LOAD(r3, &inputs[(16 * (tile) + (r3)) * COLS], 2); \
  XV_FMUL(16, r0, r0, 2); \
  XV_FMUL(18, r1, r1, 2); \
  XV_FREDUCE_SUM(RN_BATCH_MAX_DEST(r0, s0), RN_QUANT_ZERO, 16, 2); \
  XV_FREDUCE_SUM(s1, RN_QUANT_ZERO, 18, 2); \
  XV_FMUL(19, r2, r2, 2); \
  XV_FMUL(20, r3, r3, 2); \
  XV_FREDUCE_SUM(s2, RN_QUANT_ZERO, 19, 2); \
  XV_FREDUCE_SUM(s3, RN_QUANT_ZERO, 20, 2); \
} while (0)

#define PERF_COLLECT_FOUR(index, s0, s1, s2, s3) do { \
  RN_BATCH_MAX_INSERT(index, s0); \
  XV_SLIDEUP(RN_QUANT_MAXIMA, (index) + 1, s1, 2); \
  XV_SLIDEUP(RN_QUANT_MAXIMA, (index) + 2, s2, 2); \
  XV_SLIDEUP(RN_QUANT_MAXIMA, (index) + 3, s3, 2); \
} while (0)

/* The first quartet's sums survive reuse of v16/v18/v19/v20. Row zero
 * can still reduce directly into v23.E0 with the direct-maxima layout. */
#define PERF_SUM_EIGHT(tile, r0, r1, r2, r3, r4, r5, r6, r7) do { \
  PERF_SUM_FOUR(tile, r0, r1, r2, r3, 22, 21, 30, 24); \
  PERF_SUM_FOUR(tile, r4, r5, r6, r7, 16, 18, 19, 20); \
  (void)xv_setvli(16, 0x10); \
  PERF_COLLECT_FOUR(r0, 22, 21, 30, 24); \
  PERF_COLLECT_FOUR(r4, 16, 18, 19, 20); \
} while (0)

#define PERF_APPLY_FOUR(r0, r1, r2, r3, s0, s1, s2, s3) do { \
  XV_FMUL_SCALAR(r0, s0, r0); \
  XV_FMUL_SCALAR(r1, s1, r1); \
  XV_FMUL_SCALAR(r2, s2, r2); \
  XV_FMUL_SCALAR(r3, s3, r3); \
  XV_FMUL(r0, 27, r0, 2); \
  XV_FMUL(r1, 27, r1, 2); \
  XV_FMUL(r2, 27, r2, 2); \
  XV_FMUL(r3, 27, r3, 2); \
} while (0)

/* Enter at VL=16: gather one EG per reciprocal; row 0 already has v20.E0.
 * v22/v21/v30/v24 are free until quantization and span four first-EG banks. */
#define PERF_NORMALIZE_EIGHT(r0, r1, r2, r3, r4, r5, r6, r7) do { \
  RN_BATCH_ZERO(r0, (void)0, XV_GATHER_IMM(16, r0, 20, 2)); \
  XV_GATHER_IMM(18, r1, 20, 2); \
  XV_GATHER_IMM(19, r2, 20, 2); \
  XV_GATHER_IMM(23, r3, 20, 2); \
  XV_GATHER_IMM(22, r4, 20, 2); \
  XV_GATHER_IMM(21, r5, 20, 2); \
  XV_GATHER_IMM(30, r6, 20, 2); \
  XV_GATHER_IMM(24, r7, 20, 2); \
  (void)xv_setvli(COLS, 0x10); \
  PERF_APPLY_FOUR(r0, r1, r2, r3, RN_BATCH_ZERO(r0, 20, 16), 18, 19, 23); \
  PERF_APPLY_FOUR(r4, r5, r6, r7, 22, 21, 30, 24); \
} while (0)

/* BLOCK_PACK reads the positive reciprocal from bits 30:0; bit 31 keeps
 * the block. Encode that bit once per batch using the existing nonzero mask. */
#define PERF_PACK_DESCRIPTOR(inverse, mask) do { \
  RN_BATCH_SCALE_RECIPROCAL(inverse); \
  XV_FSGNJX(inverse, mask, inverse, 2); \
} while (0)

/* v27 is dead during packing; its first EG shares v30's bank. Descriptor
 * lanes 0..7 fill line A, and lanes 8..15 fill the same slots in line B. */
#define PERF_LINE_B RN_QUANT_SECOND
/* BLOCK_PACK does not use the discrete conversion factor, so v22 is free. */
#define PERF_BATCH2_MAXIMA RN_QUANT_FACTOR
#define PERF_PACK_A(source, slot, desc) \
  XV_BLOCK_PACK(RN_QUANT_PACKED, source, desc, (slot) & 3, slot)
#define PERF_PACK_B(source, slot, desc) \
  XV_BLOCK_PACK(PERF_LINE_B, source, desc, (slot) & 3, 8 + (slot))
#define PERF_PACK_PAIR(a, b, slot, desc) do { \
  PERF_PACK_A(a, slot, desc); \
  PERF_PACK_B(b, slot, desc); \
} while (0)

#define PERF_STORE_LINES(batch) do { \
  RN_BATCH_STORE_PACKED(batch, 0); \
  XV_STORE(PERF_LINE_B, &packed.data[64 * (2 * (batch) + 1)], 2); \
} while (0)

#define PERF_PACK_ONLY_PAIR(next_source, block, index, a, b, slot, desc) \
  PERF_PACK_PAIR(a, b, slot, desc)

/* Keep the checkpoint's amax scratch registers and interleave the previous
 * batch's two packs with independent work on the next batch. */
#define PERF_STEADY_PAIR(next_source, block, index, a, b, slot, desc) do { \
  RN_BATCH_EXTRACT_FIRST(2, block, next_source); \
  XV_SLIDEDOWN(3, 16 * ((block) + 1), next_source, 2); \
  XV_FABS(2, RN_BATCH_FIRST_REG(2, block, next_source), 2); \
  PERF_PACK_A(a, slot, desc); \
  XV_FABS(3, 3, 2); \
  XV_FREDUCE_MAX(RN_BATCH_MAX_DEST(index, 2), RN_QUANT_ZERO, 2, 2); \
  PERF_PACK_B(b, slot, desc); \
  XV_FREDUCE_MAX(3, RN_QUANT_ZERO, 3, 2); \
  RN_BATCH_MAX_INSERT(index, 2); \
  XV_SLIDEUP(RN_QUANT_MAXIMA, (index) + 1, 3, 2); \
} while (0)

#define PERF_PACK_TWO_SLOTS(a, b, slot, desc) do { \
  PERF_PACK_PAIR(a, b, slot, desc); \
  PERF_PACK_PAIR(a, b, (slot) + 1, desc); \
} while (0)

#define PERF_PACK_ROWS(batch, r0, r1, r2, r3, desc) do { \
  PERF_PACK_TWO_SLOTS(r0, r2, 0, desc); PERF_PACK_TWO_SLOTS(r0, r2, 2, desc); \
  PERF_PACK_TWO_SLOTS(r1, r3, 4, desc); PERF_PACK_TWO_SLOTS(r1, r3, 6, desc); \
  PERF_STORE_LINES(batch); \
} while (0)

#define PERF_OVERLAP(batch, inverse, mask, maxima, previous_desc, \
                     p0, p1, p2, p3, pair, n0, n1, n2, n3, scales_ready) do { \
  RN_BATCH_SCALE_BEGIN_FROM(inverse, mask, maxima); \
  pair(n0, 0, 0, p0, p2, 0, previous_desc); \
  RN_BATCH_SCALE_ALPHA(inverse); \
  pair(n0, 2, 2, p0, p2, 1, previous_desc); \
  RN_BATCH_SCALE_ENCODE(batch, inverse, mask, scales_ready); \
  PERF_PACK_DESCRIPTOR(inverse, mask); \
  pair(n1, 0, 4, p0, p2, 2, previous_desc); \
  pair(n1, 2, 6, p0, p2, 3, previous_desc); \
  pair(n2, 0, 8, p1, p3, 4, previous_desc); \
  pair(n2, 2, 10, p1, p3, 5, previous_desc); \
  pair(n3, 0, 12, p1, p3, 6, previous_desc); \
  pair(n3, 2, 14, p1, p3, 7, previous_desc); \
  PERF_STORE_LINES((batch) - 1); \
} while (0)

static void perf_load_gamma(void) {
  (void)xv_setvli(COLS, 0x10);
  XV_LOAD(27, &gamma[0], 2);
}

static void perf_sum_rows(unsigned tile) {
  /* The gamma load already selected VL=64 for the first eight rows. */
  PERF_SUM_EIGHT(tile, 0, 1, 2, 3, 4, 5, 6, 7);
  (void)xv_setvli(COLS, 0x10);
  PERF_SUM_EIGHT(tile, 8, 9, 10, 11, 12, 13, 14, 15);
  XV_LOAD_E0(19, &constants[1]);
  XV_LOAD_E0(18, &constants[2]);
  XV_FMUL_SCALAR(20, 19, RN_QUANT_MAXIMA);
  XV_FADD_SCALAR(20, 18, 20);
  XV_FSQRT(20, 20, 2);
  XV_FDIV(20, 20, RN_QUANT_ONE, 2);
}

static void perf_normalize_rows(void) {
  PERF_NORMALIZE_EIGHT(0, 1, 2, 3, 4, 5, 6, 7);
  (void)xv_setvli(16, 0x10);
  PERF_NORMALIZE_EIGHT(8, 9, 10, 11, 12, 13, 14, 15);
}

static void perf_quantize_rows(unsigned tile) {
  /* Collect three independent batches at normalization's VL=64. */
  XV_BLOCK_AMAX(RN_QUANT_MAXIMA, 0, 0);
  XV_BLOCK_AMAX(RN_QUANT_MAXIMA, 1, 1);
  XV_BLOCK_AMAX(RN_QUANT_MAXIMA, 2, 2);
  XV_BLOCK_AMAX(RN_QUANT_MAXIMA, 3, 3);
  XV_BLOCK_AMAX(19, 0, 4);
  XV_BLOCK_AMAX(19, 1, 5);
  XV_BLOCK_AMAX(19, 2, 6);
  XV_BLOCK_AMAX(19, 3, 7);
  XV_BLOCK_AMAX(PERF_BATCH2_MAXIMA, 0, 8);
  XV_BLOCK_AMAX(PERF_BATCH2_MAXIMA, 1, 9);
  XV_BLOCK_AMAX(PERF_BATCH2_MAXIMA, 2, 10);
  XV_BLOCK_AMAX(PERF_BATCH2_MAXIMA, 3, 11);
  (void)xv_setvli(16, 0x10);
  RN_BATCH_SCALE_BEGIN(RN_QUANT_INVERSE, 21);
  RN_BATCH_SCALE_ALPHA(RN_QUANT_INVERSE);
  RN_BATCH_SCALE_ENCODE(4 * tile, RN_QUANT_INVERSE, 21, 0);
  PERF_PACK_DESCRIPTOR(RN_QUANT_INVERSE, 21);

  PERF_PACK_ROWS(4 * tile, 0, 1, 2, 3, RN_QUANT_INVERSE);

  /* v0/v1 are free for batch 1's descriptor and mask after batch 0's pack. */
  RN_BATCH_SCALE_BEGIN_FROM(0, 1, 19);
  RN_BATCH_SCALE_ALPHA(0);
  RN_BATCH_SCALE_ENCODE(4 * tile + 1, 0, 1, 0);
  PERF_PACK_DESCRIPTOR(0, 1);
  PERF_OVERLAP(4 * tile + 2, RN_QUANT_INVERSE, 21, PERF_BATCH2_MAXIMA, 0,
    4, 5, 6, 7, PERF_STEADY_PAIR, 12, 13, 14, 15, 0);
  PERF_OVERLAP(4 * tile + 3, 0, 1, RN_QUANT_MAXIMA, RN_QUANT_INVERSE,
    8, 9, 10, 11, PERF_PACK_ONLY_PAIR, 0, 0, 0, 0, 1);
  PERF_PACK_ROWS(4 * tile + 3, 12, 13, 14, 15, 0);
}

static void execute_perf_tile(void) {
  __asm__ volatile ("fence rw, rw" ::: "memory");
  RN_QUANT_INITIALIZE();
  for (unsigned tile = 0; tile < ROWS / 16; ++tile) {
    perf_load_gamma();
    perf_sum_rows(tile);
    perf_normalize_rows();
    perf_quantize_rows(tile);
  }
}

static void initialize_inputs(void) {
  float input_values[509];
  constants[0] = 0.0f;
  constants[1] = 1.0f / COLS;
  constants[2] = epsilon;
  quant_constants[0] = 6.0f;
  quant_constants[1] = alpha;
  quant_constants[2] = 1.0f;
  quant_constants[3] = 1.0f / 512.0f;
  /* Reuse the same 509 possible input values rather than dividing 8192 times. */
  for (unsigned index = 0; index < 509; ++index) {
    const float value = (float)((int)index - 254) / 97.0f;
    input_values[index] = EVU_RMS_BF16_INPUT ? rn_bf16_round(value) : value;
  }
  for (unsigned col = 0; col < COLS; ++col) {
    const float value = 0.5f + (float)((col * 13u) % 101u) / 128.0f;
    gamma[col] = EVU_RMS_BF16_INPUT ? rn_bf16_round(value) : value;
  }
  for (unsigned row = 0; row < ROWS; ++row) {
    for (unsigned col = 0; col < COLS; ++col) {
      float value = input_values[(row * 113u + col * 37u) % 509u];
      if (row == 0 || (row % 17 == 0 && col < 16))
        value = rn_bits_float((col & 1) ? UINT32_C(0x80000000) : 0);
      inputs[row * COLS + col] = value;
    }
  }
}

__attribute__((used, noinline)) static int rmsnorm_nvfp4_tile_perf_main(void) {
  __asm__ volatile ("csrw frm, x0" ::: "memory");
  const uint64_t capability = XV_CSR_READ(XV_EVCAP);
  if (XV_CSR_READ(XV_EVLENB) != 512 || (capability & 255) != 2 ||
      !(capability & XV_CAP_RVV_MATH) || !(capability & XV_CAP_LOW_PRECISION) ||
      !(capability & XV_CAP_GATHER_IMM) || !(capability & XV_CAP_BLOCK_QUANT) ||
      !(alpha >= 0x1p-100f && alpha <= 0x1p100f) ||
      !(epsilon >= 0x1p-100f && epsilon <= 0x1p100f)) return 1;
  if (xv_setvli(COLS, 0x10) != COLS || xv_setvli(16, 0x10) != 16) return 1;
  initialize_inputs();
  for (unsigned sample = 0; sample < EVU_RMS_SAMPLES; ++sample) {
    prepare();
    const uint64_t start = read_cycle();
    execute_perf_tile();
    synchronize();
    const uint64_t end = read_cycle();
    if (end <= start || (XV_CSR_READ(XV_EVSTATUS) & 2) ||
        (XV_CSR_READ(XV_EFFLAGS) & 0x18)) return 1;
    printf("[RN-PERF] rows=128 cols=64 stage=fused_nvfp4 schedule=pack_interleaved_amax12_frontload_sum2_collect8_norm8_vl65 sample=%u cycles=%lu\n",
      sample, (unsigned long)(end - start));
  }
  return 0;
}

__attribute__((naked)) int main(void) {
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail rmsnorm_nvfp4_tile_perf_main\n");
}
