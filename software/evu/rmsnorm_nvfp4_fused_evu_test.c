/* Reuse the format-11 kernels; do not run the multi-stage test driver. */
#ifndef EVU_RMS_QUANT_DIRECT_FIRST
#define EVU_RMS_QUANT_DIRECT_FIRST 1
#endif
#if EVU_RMS_QUANT_DIRECT_FIRST != 0 && EVU_RMS_QUANT_DIRECT_FIRST != 1
#error "EVU_RMS_QUANT_DIRECT_FIRST must be 0 or 1"
#endif
#ifndef EVU_RMS_QUANT_DIRECT_MAXIMA
#define EVU_RMS_QUANT_DIRECT_MAXIMA 1
#endif
#ifndef EVU_RMS_QUANT_SCALAR_FIRST
#define EVU_RMS_QUANT_SCALAR_FIRST 1
#endif
#if EVU_RMS_QUANT_DIRECT_FIRST
/* Pair starts are 0/2/4/6. Keep nonzero extraction order unchanged. */
#define RN_FUSED_FIRST_0(copy, direct) direct
#define RN_FUSED_FIRST_2(copy, direct) copy
#define RN_FUSED_FIRST_4(copy, direct) copy
#define RN_FUSED_FIRST_6(copy, direct) copy
#define RN_FUSED_FIRST_IMPL(block, copy, direct) RN_FUSED_FIRST_##block(copy, direct)
#define RN_FUSED_FIRST(block, copy, direct) RN_FUSED_FIRST_IMPL(block, copy, direct)
#define RN_BATCH_EXTRACT_FIRST(first, block, source) \
  RN_FUSED_FIRST(block, XV_SLIDEDOWN(first, 16 * (block), source, 2), (void)0)
#define RN_BATCH_FIRST_REG(first, block, source) RN_FUSED_FIRST(block, first, source)
#endif

#define EVU_RMS_KERNELS_ONLY 1
/* The included full-suite checker/helpers are deliberately unused here. */
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused"
#include "rmsnorm_nvfp4_evu_test.c"
#pragma GCC diagnostic pop

#if EVU_RMS_ONLY
#error "The fused-only test requires NVFP4 quantization"
#endif

#ifndef EVU_RMS_FUSED_BLOCK
#define EVU_RMS_FUSED_BLOCK (EVU_RMS_QUANT_BANKED && EVU_RMS_QUANT_RENAME && EVU_RMS_QUANT_DIRECT_FIRST && EVU_RMS_QUANT_DIRECT_MAXIMA && EVU_RMS_QUANT_SCALAR_FIRST)
#endif
#if EVU_RMS_FUSED_BLOCK
#if !EVU_RMS_QUANT_BANKED
#error "Block packing needs the banked register layout"
#endif
#define FUSED_QUANT_SCHEDULE "block64_pack16"
/* Each original chunk is 128 FP32s. AMAX and PACK can select only the
 * first 64, so keep the upper halves in v19/v22 until both lines are stored. */
#define FUSED_PACK_PAIR(a, b, group, index) do { \
  XV_BLOCK_PACK(30, a, 20, group, index); \
  XV_BLOCK_PACK(27, b, 20, group, 8 + (index)); \
} while (0)
#define FUSED_BATCH(batch, a, b, scales_ready) do { \
  (void)xv_setvli(64, 0x10); \
  XV_SLIDEDOWN(19, 64, a, 2); XV_SLIDEDOWN(22, 64, b, 2); \
  XV_BLOCK_AMAX(23, 0, a); XV_BLOCK_AMAX(23, 1, 19); \
  XV_BLOCK_AMAX(23, 2, b); XV_BLOCK_AMAX(23, 3, 22); \
  (void)xv_setvli(16, 0x10); \
  RN_BATCH_SCALE_BEGIN(20, 21); RN_BATCH_SCALE_ALPHA(20); \
  RN_BATCH_SCALE_ENCODE(batch, 20, 21, scales_ready); \
  RN_BATCH_SCALE_RECIPROCAL(20); XV_FSGNJX(20, 21, 20, 2); \
  FUSED_PACK_PAIR(a, b, 0, 0); FUSED_PACK_PAIR(a, b, 1, 1); \
  FUSED_PACK_PAIR(a, b, 2, 2); FUSED_PACK_PAIR(a, b, 3, 3); \
  FUSED_PACK_PAIR(19, 22, 0, 4); FUSED_PACK_PAIR(19, 22, 1, 5); \
  FUSED_PACK_PAIR(19, 22, 2, 6); FUSED_PACK_PAIR(19, 22, 3, 7); \
  RN_BATCH_STORE_PACKED(batch, 0); XV_STORE(27, &packed.data[128 * (batch) + 64], 2); \
} while (0)

static void execute_fused(void) {
  load_operands(0);
  seed_sums();
  sum_of_squares(0);
  /* The four E0 sums are independent. One vector SQRT/DIV processes all
   * four without changing the per-row reduction or its rounding order. */
  (void)xv_setvli(4, 0x10);
  XV_SLIDEUP(24, 1, 25, 2); XV_SLIDEUP(24, 2, 26, 2); XV_SLIDEUP(24, 3, 27, 2);
  XV_FMUL_SCALAR(24, 29, 24); XV_FADD_SCALAR(24, 30, 24);
  XV_BROADCAST(20, 31, 2); XV_FSQRT(24, 24, 2); XV_FDIV(24, 24, 20, 2);
  XV_GATHER_IMM(25, 1, 24, 2); XV_GATHER_IMM(26, 2, 24, 2); XV_GATHER_IMM(27, 3, 24, 2);
  (void)xv_setvli(CHUNK, 0x10);
  normalize(0);
  RN_QUANT_INIT_WITH((void)0);
  FUSED_BATCH(0, 0, 1, 0); FUSED_BATCH(1, 2, 3, 0);
  FUSED_BATCH(2, 4, 5, 0); FUSED_BATCH(3, 6, 7, 1);
  FUSED_BATCH(4, 8, 9, 0); FUSED_BATCH(5, 10, 11, 0);
  FUSED_BATCH(6, 12, 13, 0); FUSED_BATCH(7, 14, 15, 1);
}
#else
#define FUSED_QUANT_SCHEDULE "overlap"
static void execute_fused(void) { execute(FUSED_NVFP4, 0); }
#endif

/* Match the DLEN=512 four-slot streaming reduction, slot merges and half folds.
 * Keep every add at FP32 precision, as in NEMU's fp_unordered_sum. */
static float fused_reference_sum(const float *values, float seed) {
  float accumulator[4][16] = {{0}};
  accumulator[0][0] = seed;
  for (unsigned index = 0; index < CHUNK; ++index)
    accumulator[(index / 16) % 4][index % 16] += values[index];
  for (unsigned slot = 1; slot < 4; ++slot)
    for (unsigned lane = 0; lane < 16; ++lane)
      accumulator[0][lane] += accumulator[slot][lane];
  for (unsigned half = 8; half; half /= 2)
    for (unsigned lane = 0; lane < half; ++lane)
      accumulator[0][lane] += accumulator[0][lane + half];
  return accumulator[0][0];
}

static void fused_reference(void) {
  for (unsigned row = 0; row < ROWS; ++row) {
    float lanes[CHUNK];
    float sum = 0.0f;
    for (unsigned chunk = 0; chunk < COLS / CHUNK; ++chunk) {
      for (unsigned lane = 0; lane < CHUNK; ++lane) {
        const float value = inputs[row * COLS + chunk * CHUNK + lane];
        const float square = value * value;
#if EVU_RMS_LANE_SUM
        lanes[lane] = chunk ? lanes[lane] + square : square;
#else
        lanes[lane] = square;
#endif
      }
#if !EVU_RMS_LANE_SUM
      sum = fused_reference_sum(lanes, sum);
#endif
    }
#if EVU_RMS_LANE_SUM
    sum = fused_reference_sum(lanes, 0.0f);
#endif
    const float mean = sum * (1.0f / 512.0f);
    const float inverse = reference_rsqrt(mean + epsilon);
    for (unsigned col = 0; col < COLS; ++col) {
      const float scaled = inputs[row * COLS + col] * inverse;
      reference_y[row * COLS + col] = scaled * gamma[col];
    }
  }
  rn_block_amax(reference_y, reference_maxima, BLOCKS);
  rn_block_scales(reference_maxima, alpha, reference_scales, reference_inverses, BLOCKS);
  rn_pack_fp4(reference_y, reference_inverses, reference_packed, BLOCKS);
}

static unsigned check_fused(void) {
  unsigned errors = memcmp(scales.data, reference_scales, sizeof(reference_scales)) != 0;
  errors += memcmp(packed.data, reference_packed, sizeof(reference_packed)) != 0;
  errors += check_guards(normalized.before, normalized.after);
  errors += check_guards(scales.before, scales.after);
  errors += check_guards(packed.before, packed.after);
  errors += !!(XV_CSR_READ(XV_EVSTATUS) & 2);
  errors += !!(XV_CSR_READ(XV_EFFLAGS) & 0x18);
  errors += !!(XV_CSR_READ(0x001) & 0x1c);
  return errors;
}

__attribute__((used, noinline)) static int rmsnorm_nvfp4_fused_test_main(void) {
  __asm__ volatile ("csrw frm, x0" ::: "memory");
  const uint64_t required = XV_CAP_RETURNED_VL | XV_CAP_RVV_MATH | XV_CAP_LOW_PRECISION |
      XV_CAP_GATHER_IMM | (EVU_RMS_FUSED_BLOCK ? XV_CAP_BLOCK_QUANT : 0);
  if (xv_init(512, required) ||
      !(alpha >= 0x1p-100f && alpha <= 0x1p100f) ||
      !(epsilon >= 0x1p-100f && epsilon <= 0x1p100f)) {
    printf("[RN] FAIL unsupported EVU configuration or invalid parameters\n");
    return 1;
  }
  if (xv_setvli(CHUNK, 0x10) != CHUNK) {
    printf("[RN] FAIL unexpected VL\n");
    return 1;
  }
  initialize();
  fused_reference();
  printf("[RN] config rows=4 cols=512 samples=%u input=%s alpha_bits=%08x eps_bits=%08x warmup=0 cases=1 format=11 quant_batch=16 quant_vl=16 quant_sew=32 quant_schedule=" FUSED_QUANT_SCHEDULE " lane_sum=%u quant_rename=%u quant_banked=%u quant_direct_first=%u quant_direct_maxima=%u quant_scalar_first=%u workload=rmsnorm_nvfp4_evu quantization=evu profile=fused_row_major oracle_dlen=512\n",
    (unsigned)EVU_RMS_SAMPLES, EVU_RMS_BF16_INPUT ? "bf16_rounded_fp32" : "fp32",
    rn_float_bits(alpha), rn_float_bits(epsilon), (unsigned)EVU_RMS_LANE_SUM,
    (unsigned)EVU_RMS_QUANT_RENAME, (unsigned)EVU_RMS_QUANT_BANKED,
    (unsigned)EVU_RMS_QUANT_DIRECT_FIRST, (unsigned)EVU_RMS_QUANT_DIRECT_MAXIMA,
    (unsigned)EVU_RMS_QUANT_SCALAR_FIRST);
  uint64_t cycles[EVU_RMS_SAMPLES], retired[EVU_RMS_SAMPLES];
  for (unsigned sample = 0; sample < EVU_RMS_SAMPLES; ++sample) {
    prepare(FUSED_NVFP4, 0);
    const uint64_t start_fence = fence_count - 1;
    const uint64_t start_instructions = read_instret();
    const uint64_t start = read_cycle();
    execute_fused();
    synchronize();
    const uint64_t end = read_cycle();
    const uint64_t instructions = read_instret() - start_instructions;
    const uint64_t end_fence = fence_count - 1;
    const unsigned errors = check_fused() + (end <= start);
    cycles[sample] = end - start;
    retired[sample] = instructions;
    printf("[RN-SAMPLE] stage=fused_nvfp4 schedule=row_major sample=%u cycles=%lu instructions=%lu errors=%u fences=%lu:%lu\n",
      sample, (unsigned long)cycles[sample], (unsigned long)instructions, errors,
      (unsigned long)start_fence, (unsigned long)end_fence);
    if (errors) {
      printf("[RN] FAIL stage=fused_nvfp4 schedule=row_major sample=%u\n", sample);
      return 1;
    }
  }
  if (EVU_RMS_SAMPLES == 1) {
    printf("[RN] stage=fused_nvfp4 schedule=row_major cycles=%lu inst=%lu errors=0\n",
      (unsigned long)cycles[0], (unsigned long)retired[0]);
  } else {
    sort_samples(cycles);
    sort_samples(retired);
    printf("[RN] stage=fused_nvfp4 schedule=row_major n=%u min=%lu median=%lu max=%lu inst=%lu errors=0\n",
      (unsigned)EVU_RMS_SAMPLES, (unsigned long)cycles[0], (unsigned long)sample_median(cycles),
      (unsigned long)cycles[EVU_RMS_SAMPLES - 1], (unsigned long)sample_median(retired));
  }
  printf("[RN] PASS\n");
  return 0;
}

__attribute__((naked)) int main(void) {
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail rmsnorm_nvfp4_fused_test_main\n");
}
