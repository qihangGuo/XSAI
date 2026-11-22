/* Reuse the checked test's storage, ABI helpers and quantization primitives. */
#define EVU_RMS_TILE_KERNELS_ONLY 1
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused"
#include "rmsnorm_nvfp4_tile128x64_evu_test.c"
#pragma GCC diagnostic pop
#if !EVU_RMS_QUANT_BANKED
#error "The optimized tile schedule requires the bank-aware register layout"
#endif

/* v0-v15 hold sixteen 64-element rows. Four scratch registers collect row
 * sums into v23; v20 then holds sixteen RMS reciprocals, one per row. */
#define PERF_SUM_FOUR(tile, r0, r1, r2, r3, index) do { \
  (void)xv_setvli(COLS, 0x10); \
  XV_LOAD(r0, &inputs[(16 * (tile) + (r0)) * COLS], 2); \
  XV_LOAD(r1, &inputs[(16 * (tile) + (r1)) * COLS], 2); \
  XV_LOAD(r2, &inputs[(16 * (tile) + (r2)) * COLS], 2); \
  XV_LOAD(r3, &inputs[(16 * (tile) + (r3)) * COLS], 2); \
  XV_FMUL(16, r0, r0, 2); \
  XV_FMUL(18, r1, r1, 2); \
  XV_FMUL(19, r2, r2, 2); \
  XV_FMUL(20, r3, r3, 2); \
  XV_FREDUCE_SUM(RN_BATCH_MAX_DEST(index, 16), RN_QUANT_ZERO, 16, 2); \
  XV_FREDUCE_SUM(18, RN_QUANT_ZERO, 18, 2); \
  XV_FREDUCE_SUM(19, RN_QUANT_ZERO, 19, 2); \
  XV_FREDUCE_SUM(20, RN_QUANT_ZERO, 20, 2); \
  (void)xv_setvli(16, 0x10); \
  RN_BATCH_MAX_INSERT(index, 16); \
  XV_SLIDEUP(RN_QUANT_MAXIMA, (index) + 1, 18, 2); \
  XV_SLIDEUP(RN_QUANT_MAXIMA, (index) + 2, 19, 2); \
  XV_SLIDEUP(RN_QUANT_MAXIMA, (index) + 3, 20, 2); \
} while (0)

/* Gather only one EG, then use E0 for the entire row. Broadcasting at VL=64
 * would waste four integer issues per reciprocal instead of one. Row register
 * numbers also index v20; .insn requires literal register names, not sums. */
#define PERF_NORMALIZE_FOUR(r0, r1, r2, r3) do { \
  RN_BATCH_ZERO(r0, (void)0, XV_GATHER_IMM(16, r0, 20, 2)); \
  XV_GATHER_IMM(18, r1, 20, 2); \
  XV_GATHER_IMM(19, r2, 20, 2); \
  XV_GATHER_IMM(23, r3, 20, 2); \
  (void)xv_setvli(COLS, 0x10); \
  XV_FMUL_SCALAR(r0, RN_BATCH_ZERO(r0, 20, 16), r0); \
  XV_FMUL_SCALAR(r1, 18, r1); \
  XV_FMUL_SCALAR(r2, 19, r2); \
  XV_FMUL_SCALAR(r3, 23, r3); \
  XV_FMUL(r0, 27, r0, 2); \
  XV_FMUL(r1, 27, r1, 2); \
  XV_FMUL(r2, 27, r2, 2); \
  XV_FMUL(r3, 27, r3, 2); \
  (void)xv_setvli(16, 0x10); \
} while (0)

#define PERF_MAX_ROWS(r0, r1, r2, r3) do { \
  RN_BATCH_MAX_PAIR(r0, 0, 0); RN_BATCH_MAX_PAIR(r0, 2, 2); \
  RN_BATCH_MAX_PAIR(r1, 0, 4); RN_BATCH_MAX_PAIR(r1, 2, 6); \
  RN_BATCH_MAX_PAIR(r2, 0, 8); RN_BATCH_MAX_PAIR(r2, 2, 10); \
  RN_BATCH_MAX_PAIR(r3, 0, 12); RN_BATCH_MAX_PAIR(r3, 2, 14); \
} while (0)

#define PERF_PACK_ROWS(batch, r0, r1, r2, r3, inverse, mask) do { \
  RN_BATCH_PACK_PAIR(r0, 0, 0, 1, inverse, mask); \
  RN_BATCH_PACK_PAIR(r0, 2, 2, 3, inverse, mask); \
  RN_BATCH_PACK_PAIR(r1, 0, 4, 5, inverse, mask); \
  RN_BATCH_PACK_PAIR(r1, 2, 6, 7, inverse, mask); \
  RN_BATCH_STORE_PACKED(batch, 0); \
  RN_BATCH_PACK_PAIR(r2, 0, 8, 9, inverse, mask); \
  RN_BATCH_PACK_PAIR(r2, 2, 10, 11, inverse, mask); \
  RN_BATCH_PACK_PAIR(r3, 0, 12, 13, inverse, mask); \
  RN_BATCH_PACK_PAIR(r3, 2, 14, 15, inverse, mask); \
  RN_BATCH_STORE_PACKED(batch, 1); \
} while (0)

#define PERF_PRIME(batch, inverse, mask, n0, n1, n2, n3) do { \
  RN_BATCH_SCALE_BEGIN(inverse, mask); \
  RN_BATCH_MAX_PAIR(n0, 0, 0); RN_BATCH_MAX_PAIR(n0, 2, 2); \
  RN_BATCH_SCALE_ALPHA(inverse); \
  RN_BATCH_MAX_PAIR(n1, 0, 4); RN_BATCH_MAX_PAIR(n1, 2, 6); \
  RN_BATCH_SCALE_ENCODE(batch, inverse, mask, 0); \
  RN_BATCH_SCALE_RECIPROCAL(inverse); \
  RN_BATCH_MAX_PAIR(n2, 0, 8); RN_BATCH_MAX_PAIR(n2, 2, 10); \
  RN_BATCH_MAX_PAIR(n3, 0, 12); RN_BATCH_MAX_PAIR(n3, 2, 14); \
} while (0)

#define PERF_OVERLAP(batch, inverse, mask, previous_inverse, previous_mask, \
                     p0, p1, p2, p3, pair, n0, n1, n2, n3, scales_ready) do { \
  RN_BATCH_SCALE_BEGIN(inverse, mask); \
  pair(n0, p0, 0, 0, 1, previous_inverse, previous_mask); \
  RN_BATCH_SCALE_ALPHA(inverse); \
  pair(n0, p0, 2, 2, 3, previous_inverse, previous_mask); \
  RN_BATCH_SCALE_ENCODE(batch, inverse, mask, scales_ready); \
  RN_BATCH_SCALE_RECIPROCAL(inverse); \
  pair(n1, p1, 0, 4, 5, previous_inverse, previous_mask); \
  pair(n1, p1, 2, 6, 7, previous_inverse, previous_mask); \
  RN_BATCH_STORE_PACKED((batch) - 1, 0); \
  pair(n2, p2, 0, 8, 9, previous_inverse, previous_mask); \
  pair(n2, p2, 2, 10, 11, previous_inverse, previous_mask); \
  pair(n3, p3, 0, 12, 13, previous_inverse, previous_mask); \
  pair(n3, p3, 2, 14, 15, previous_inverse, previous_mask); \
  RN_BATCH_STORE_PACKED((batch) - 1, 1); \
} while (0)

static void perf_load_gamma(void) {
  (void)xv_setvli(COLS, 0x10);
  XV_LOAD(27, &gamma[0], 2);
}

static void perf_sum_rows(unsigned tile) {
  PERF_SUM_FOUR(tile, 0, 1, 2, 3, 0);
  PERF_SUM_FOUR(tile, 4, 5, 6, 7, 4);
  PERF_SUM_FOUR(tile, 8, 9, 10, 11, 8);
  PERF_SUM_FOUR(tile, 12, 13, 14, 15, 12);
  XV_LOAD_E0(19, &constants[1]);
  XV_LOAD_E0(18, &constants[2]);
  XV_FMUL_SCALAR(20, 19, RN_QUANT_MAXIMA);
  XV_FADD_SCALAR(20, 18, 20);
  XV_FSQRT(20, 20, 2);
  XV_FDIV(20, 20, RN_QUANT_ONE, 2);
}

static void perf_normalize_rows(void) {
  PERF_NORMALIZE_FOUR(0, 1, 2, 3);
  PERF_NORMALIZE_FOUR(4, 5, 6, 7);
  PERF_NORMALIZE_FOUR(8, 9, 10, 11);
  PERF_NORMALIZE_FOUR(12, 13, 14, 15);
}

static void perf_quantize_rows(unsigned tile) {
  PERF_MAX_ROWS(0, 1, 2, 3);
  PERF_PRIME(4 * tile, RN_QUANT_INVERSE, 21, 4, 5, 6, 7);
  PERF_PACK_ROWS(4 * tile, 0, 1, 2, 3, RN_QUANT_INVERSE, 21);
  /* v0-v3 are dead after the first four rows have been packed. */
  PERF_PRIME(4 * tile + 1, 0, 1, 8, 9, 10, 11);
  PERF_OVERLAP(4 * tile + 2, RN_QUANT_INVERSE, 21, 0, 1,
    4, 5, 6, 7, RN_BATCH_STEADY_PAIR, 12, 13, 14, 15, 0);
  PERF_OVERLAP(4 * tile + 3, 0, 1, RN_QUANT_INVERSE, 21,
    8, 9, 10, 11, RN_BATCH_PACK_ONLY_PAIR, 0, 0, 0, 0, 1);
  PERF_PACK_ROWS(4 * tile + 3, 12, 13, 14, 15, 0, 1);
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
      !(capability & XV_CAP_GATHER_IMM) ||
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
    printf("[RN-PERF] rows=128 cols=64 stage=fused_nvfp4 schedule=row_major sample=%u cycles=%lu\n",
      sample, (unsigned long)(end - start));
  }
  return 0;
}

__attribute__((naked)) int main(void) {
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail rmsnorm_nvfp4_tile_perf_main\n");
}
