#include <klib.h>
#include <stdint.h>
#include "evu_csr.h"
#include "rmsnorm_nvfp4_ref.h"

#ifndef EVU_RMS_SAMPLES
#define EVU_RMS_SAMPLES 1
#endif
#ifndef EVU_RMS_BF16_INPUT
#define EVU_RMS_BF16_INPUT 0
#endif
#ifndef EVU_RMS_EPS
#define EVU_RMS_EPS 1.0e-5f
#endif
#ifndef EVU_RMS_ALPHA
#define EVU_RMS_ALPHA (1.0f / 448.0f)
#endif
#if EVU_RMS_SAMPLES < 1
#error "EVU_RMS_SAMPLES must be positive"
#endif
#if EVU_RMS_BF16_INPUT != 0 && EVU_RMS_BF16_INPUT != 1
#error "EVU_RMS_BF16_INPUT must be 0 or 1"
#endif

/* Each resident row has four blocks. Block zero is already at E0. */
#define RN_TILE_FIRST_0(copy, direct) direct
#define RN_TILE_FIRST_2(copy, direct) copy
#define RN_TILE_FIRST_IMPL(block, copy, direct) RN_TILE_FIRST_##block(copy, direct)
#define RN_TILE_FIRST(block, copy, direct) RN_TILE_FIRST_IMPL(block, copy, direct)
#define RN_BATCH_EXTRACT_FIRST(first, block, source) \
  RN_TILE_FIRST(block, XV_SLIDEDOWN(first, 16 * (block), source, 2), (void)0)
#define RN_BATCH_FIRST_REG(first, block, source) RN_TILE_FIRST(block, first, source)
#define EVU_RMS_QUANT_DIRECT_MAXIMA 1
#define EVU_RMS_QUANT_SCALAR_FIRST 1
#include "rmsnorm_nvfp4_batch.h"

enum { ROWS = 128, COLS = 64, ROW_BATCH = 4, BATCHES = ROWS / ROW_BATCH,
       ELEMENTS = ROWS * COLS, BLOCKS = ELEMENTS / 16, GUARD = 64 };
#define ALIGN __attribute__((aligned(64)))
static float inputs[ELEMENTS] ALIGN;
static float gamma[COLS] ALIGN;
static float constants[3] ALIGN;
static float quant_constants[4] ALIGN;
static uint32_t quant_integers[2] ALIGN = {1, 127};
static volatile float epsilon = EVU_RMS_EPS;
static volatile float alpha = EVU_RMS_ALPHA;
#ifndef EVU_RMS_TILE_KERNELS_ONLY
static float reference_y[ELEMENTS] ALIGN;
static float reference_maxima[BLOCKS] ALIGN;
static float reference_inverses[BLOCKS] ALIGN;
static uint8_t reference_scales[BLOCKS] ALIGN;
static uint8_t reference_packed[ELEMENTS / 2] ALIGN;
#endif
static struct { uint8_t before[GUARD]; uint8_t data[BLOCKS]; uint8_t after[GUARD]; } scales ALIGN;
static struct { uint8_t before[GUARD]; uint8_t data[ELEMENTS / 2]; uint8_t after[GUARD]; } packed ALIGN;
static uint64_t fence_count;

static inline uint64_t read_cycle(void) {
  uint64_t value;
  __asm__ volatile ("rdcycle %0" : "=r"(value) :: "memory");
  return value;
}

#ifndef EVU_RMS_TILE_KERNELS_ONLY
static inline uint64_t read_instret(void) {
  uint64_t value;
  __asm__ volatile ("rdinstret %0" : "=r"(value) :: "memory");
  return value;
}
#endif

static void synchronize(void) {
  XV_FENCE();
  __asm__ volatile ("fence rw, rw" ::: "memory");
  ++fence_count;
}

#ifndef EVU_RMS_TILE_KERNELS_ONLY
static float reference_rsqrt(float value) {
  float root, result;
  const float one = 1.0f;
  __asm__ volatile ("fsqrt.s %0, %1" : "=f"(root) : "f"(value));
  __asm__ volatile ("fdiv.s %0, %1, %2" : "=f"(result) : "f"(one), "f"(root));
  return result;
}

/* Match the DLEN=512 four-slot FP32 reduction and its half-fold order. */
static float reference_sum(const float *values) {
  float accumulator[4][16] = {{0}};
  for (unsigned col = 0; col < COLS; ++col)
    accumulator[col / 16][col % 16] += values[col];
  for (unsigned slot = 1; slot < 4; ++slot)
    for (unsigned lane = 0; lane < 16; ++lane)
      accumulator[0][lane] += accumulator[slot][lane];
  for (unsigned half = 8; half; half /= 2)
    for (unsigned lane = 0; lane < half; ++lane)
      accumulator[0][lane] += accumulator[0][lane + half];
  return accumulator[0][0];
}

static void initialize(void) {
  constants[0] = 0.0f;
  constants[1] = 1.0f / COLS;
  constants[2] = epsilon;
  quant_constants[0] = 6.0f;
  quant_constants[1] = alpha;
  quant_constants[2] = 1.0f;
  /* E4M3's minimum positive scale; unrelated to the 64-column mean. */
  quant_constants[3] = 1.0f / 512.0f;
  for (unsigned col = 0; col < COLS; ++col) {
    const float value = 0.5f + (float)((col * 13u) % 101u) / 128.0f;
    gamma[col] = EVU_RMS_BF16_INPUT ? rn_bf16_round(value) : value;
  }
  for (unsigned row = 0; row < ROWS; ++row) {
    float squares[COLS];
    for (unsigned col = 0; col < COLS; ++col) {
      float value = (float)((int)((row * 113u + col * 37u) % 509u) - 254) / 97.0f;
      if (row == 0 || (row % 17 == 0 && col < 16))
        value = rn_bits_float((col & 1) ? UINT32_C(0x80000000) : 0);
      if (EVU_RMS_BF16_INPUT) value = rn_bf16_round(value);
      inputs[row * COLS + col] = value;
      squares[col] = value * value;
    }
    const float mean = reference_sum(squares) * (1.0f / COLS);
    const float inverse = reference_rsqrt(mean + epsilon);
    for (unsigned col = 0; col < COLS; ++col) {
      const float scaled = inputs[row * COLS + col] * inverse;
      reference_y[row * COLS + col] = scaled * gamma[col];
    }
  }
  rn_block_amax(reference_y, reference_maxima, BLOCKS);
  rn_block_scales(reference_maxima, alpha, reference_scales, reference_inverses, BLOCKS);
  rn_pack_fp4(reference_y, reference_inverses, reference_packed, BLOCKS);
  memset(scales.before, 0xa5, GUARD);
  memset(scales.after, 0x5a, GUARD);
  memset(packed.before, 0xa5, GUARD);
  memset(packed.after, 0x5a, GUARD);
}
#endif

/* v0-v3: rows; v8-v11: row states; v12: shared gamma; v14/v15: mean/epsilon.
 * Quantization owns v16-v31. In particular, v24 keeps four batches of scales. */
static void load_tile_constants(void) {
  RN_QUANT_INITIALIZE();
  XV_LOAD_E0(14, &constants[1]);
  XV_LOAD_E0(15, &constants[2]);
  (void)xv_setvli(COLS, 0x10);
  XV_LOAD(12, &gamma[0], 2);
}

#define TILE_ROW_STATE(state) do { \
  XV_FMUL_E0(state, 14, state); \
  XV_FADD_E0(state, 15, state); \
  XV_FSQRT_E0(state, state); \
  XV_FDIV_E0(state, state, RN_QUANT_ONE); \
} while (0)

#define TILE_NORMALIZE(row, state) do { \
  XV_FMUL_SCALAR(row, state, row); \
  XV_FMUL(row, 12, row, 2); \
} while (0)

static void quantize_batch(unsigned batch) {
  (void)xv_setvli(16, 0x10);
  RN_BATCH_MAX_PAIR(0, 0, 0);
  RN_BATCH_MAX_PAIR(0, 2, 2);
  RN_BATCH_MAX_PAIR(1, 0, 4);
  RN_BATCH_MAX_PAIR(1, 2, 6);
  RN_BATCH_MAX_PAIR(2, 0, 8);
  RN_BATCH_MAX_PAIR(2, 2, 10);
  RN_BATCH_MAX_PAIR(3, 0, 12);
  RN_BATCH_MAX_PAIR(3, 2, 14);
  RN_BATCH_SCALE_BEGIN(RN_QUANT_INVERSE, 21);
  RN_BATCH_SCALE_ALPHA(RN_QUANT_INVERSE);
  RN_BATCH_SCALE_ENCODE(batch, RN_QUANT_INVERSE, 21, 0);
  RN_BATCH_SCALE_RECIPROCAL(RN_QUANT_INVERSE);
  RN_BATCH_PACK_PAIR(0, 0, 0, 1, RN_QUANT_INVERSE, 21);
  RN_BATCH_PACK_PAIR(0, 2, 2, 3, RN_QUANT_INVERSE, 21);
  RN_BATCH_PACK_PAIR(1, 0, 4, 5, RN_QUANT_INVERSE, 21);
  RN_BATCH_PACK_PAIR(1, 2, 6, 7, RN_QUANT_INVERSE, 21);
  RN_BATCH_STORE_PACKED(batch, 0);
  RN_BATCH_PACK_PAIR(2, 0, 8, 9, RN_QUANT_INVERSE, 21);
  RN_BATCH_PACK_PAIR(2, 2, 10, 11, RN_QUANT_INVERSE, 21);
  RN_BATCH_PACK_PAIR(3, 0, 12, 13, RN_QUANT_INVERSE, 21);
  RN_BATCH_PACK_PAIR(3, 2, 14, 15, RN_QUANT_INVERSE, 21);
  RN_BATCH_STORE_PACKED(batch, 1);
}

static void tile_batch(unsigned batch) {
  (void)xv_setvli(COLS, 0x10);
  XV_LOAD(0, &inputs[(ROW_BATCH * batch + 0) * COLS], 2);
  XV_LOAD(1, &inputs[(ROW_BATCH * batch + 1) * COLS], 2);
  XV_LOAD(2, &inputs[(ROW_BATCH * batch + 2) * COLS], 2);
  XV_LOAD(3, &inputs[(ROW_BATCH * batch + 3) * COLS], 2);
  XV_FMUL(8, 0, 0, 2);
  XV_FMUL(9, 1, 1, 2);
  XV_FMUL(10, 2, 2, 2);
  XV_FMUL(11, 3, 3, 2);
  XV_FREDUCE_SUM(8, RN_QUANT_ZERO, 8, 2);
  XV_FREDUCE_SUM(9, RN_QUANT_ZERO, 9, 2);
  XV_FREDUCE_SUM(10, RN_QUANT_ZERO, 10, 2);
  XV_FREDUCE_SUM(11, RN_QUANT_ZERO, 11, 2);
  TILE_ROW_STATE(8);
  TILE_ROW_STATE(9);
  TILE_ROW_STATE(10);
  TILE_ROW_STATE(11);
  TILE_NORMALIZE(0, 8);
  TILE_NORMALIZE(1, 9);
  TILE_NORMALIZE(2, 10);
  TILE_NORMALIZE(3, 11);
  quantize_batch(batch);
}

static void execute_tile(void) {
  __asm__ volatile ("fence rw, rw" ::: "memory");
  load_tile_constants();
  for (unsigned batch = 0; batch < BATCHES; batch += 4) {
    tile_batch(batch);
    tile_batch(batch + 1);
    tile_batch(batch + 2);
    tile_batch(batch + 3);
    /* Four 16-byte scale batches now fill one aligned cache line. */
    XV_STORE(24, &scales.data[16 * batch], 2);
  }
}

static void prepare(void) {
  memset(scales.data, 0xcc, sizeof(scales.data));
  memset(packed.data, 0xcc, sizeof(packed.data));
  synchronize();
  XV_CSR_WRITE(XV_EFFLAGS, 0);
  __asm__ volatile ("csrw fflags, x0" ::: "memory");
}

#ifndef EVU_RMS_TILE_KERNELS_ONLY
static unsigned check_bytes(const uint8_t *actual, const uint8_t *expected,
                            unsigned count, const char *name) {
  unsigned errors = 0;
  for (unsigned index = 0; index < count; ++index) {
    if (actual[index] == expected[index]) continue;
    if (errors < 4)
      printf("[RN-CHECK] buffer=%s byte=%u actual=%02x expected=%02x\n",
        name, index, (unsigned)actual[index], (unsigned)expected[index]);
    ++errors;
  }
  return errors;
}

static unsigned check_guards(const uint8_t *before, const uint8_t *after) {
  unsigned errors = 0;
  for (unsigned index = 0; index < GUARD; ++index)
    errors += before[index] != 0xa5 || after[index] != 0x5a;
  return errors;
}

static unsigned check_fused(void) {
  unsigned errors = check_bytes(scales.data, reference_scales, sizeof(reference_scales), "scale");
  errors += check_bytes(packed.data, reference_packed, sizeof(reference_packed), "fp4");
  errors += check_guards(scales.before, scales.after);
  errors += check_guards(packed.before, packed.after);
  errors += !!(XV_CSR_READ(XV_EVSTATUS) & 2);
  errors += !!(XV_CSR_READ(XV_EFFLAGS) & 0x18);
  errors += !!(XV_CSR_READ(0x001) & 0x1c);
  return errors;
}

#endif

#ifndef EVU_RMS_TILE_KERNELS_ONLY
static void sort_samples(uint64_t *values) {
  for (unsigned index = 1; index < EVU_RMS_SAMPLES; ++index) {
    const uint64_t value = values[index];
    unsigned position = index;
    while (position && values[position - 1] > value) {
      values[position] = values[position - 1];
      --position;
    }
    values[position] = value;
  }
}

static uint64_t sample_median(const uint64_t *values) {
  return EVU_RMS_SAMPLES & 1 ? values[EVU_RMS_SAMPLES / 2] :
    (values[EVU_RMS_SAMPLES / 2 - 1] + values[EVU_RMS_SAMPLES / 2]) / 2;
}

__attribute__((used, noinline)) static int rmsnorm_nvfp4_tile_test_main(void) {
  __asm__ volatile ("csrw frm, x0" ::: "memory");
  const uint64_t required = XV_CAP_RETURNED_VL | XV_CAP_RVV_MATH |
                            XV_CAP_LOW_PRECISION | XV_CAP_GATHER_IMM;
  if (xv_init(512, required) ||
      !(alpha >= 0x1p-100f && alpha <= 0x1p100f) ||
      !(epsilon >= 0x1p-100f && epsilon <= 0x1p100f)) {
    printf("[RN] FAIL unsupported EVU configuration or invalid parameters\n");
    return 1;
  }
  if (xv_setvli(COLS, 0x10) != COLS || xv_setvli(16, 0x10) != 16) {
    printf("[RN] FAIL unexpected VL\n");
    return 1;
  }
  initialize();
  printf("[RN] config rows=128 cols=64 samples=%u input=%s alpha_bits=%08x eps_bits=%08x warmup=0 cases=1 format=12 quant_batch=16 quant_vl=16 quant_sew=32 quant_schedule=batch_rows4 row_batch=4 quant_banked=%u workload=rmsnorm_nvfp4_evu quantization=evu profile=fused_tile128x64_row_major oracle_dlen=512\n",
    (unsigned)EVU_RMS_SAMPLES, EVU_RMS_BF16_INPUT ? "bf16_rounded_fp32" : "fp32",
    rn_float_bits(alpha), rn_float_bits(epsilon), (unsigned)EVU_RMS_QUANT_BANKED);
  uint64_t cycles[EVU_RMS_SAMPLES], retired[EVU_RMS_SAMPLES];
  for (unsigned sample = 0; sample < EVU_RMS_SAMPLES; ++sample) {
    prepare();
    const uint64_t start_fence = fence_count - 1;
    const uint64_t start_instructions = read_instret();
    const uint64_t start = read_cycle();
    execute_tile();
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
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail rmsnorm_nvfp4_tile_test_main\n");
}
#endif
