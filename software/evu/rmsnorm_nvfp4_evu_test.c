#include <klib.h>
#include <stdint.h>
#include "evu_csr.h"
#include "rmsnorm_nvfp4_ref.h"
#include "rmsnorm_nvfp4_batch.h"

#ifndef EVU_RMS_SAMPLES
#define EVU_RMS_SAMPLES 1
#endif
#ifndef EVU_RMS_BF16_INPUT
#define EVU_RMS_BF16_INPUT 0
#endif
#ifndef EVU_RMS_VERBOSE
#define EVU_RMS_VERBOSE 0
#endif
#ifndef EVU_RMS_EPS
#define EVU_RMS_EPS 1.0e-5f
#endif
#ifndef EVU_RMS_ALPHA
#define EVU_RMS_ALPHA (1.0f / 448.0f)
#endif
#ifndef EVU_RMS_ONLY
#define EVU_RMS_ONLY 0
#endif
#ifndef EVU_RMS_LANE_SUM
#define EVU_RMS_LANE_SUM 1
#endif
#if EVU_RMS_LANE_SUM != 0 && EVU_RMS_LANE_SUM != 1
#error "EVU_RMS_LANE_SUM must be 0 or 1"
#endif
#if EVU_RMS_ONLY != 0 && EVU_RMS_ONLY != 1
#error "EVU_RMS_ONLY must be 0 or 1"
#endif
#if EVU_RMS_SAMPLES < 1
#error "EVU_RMS_SAMPLES must be positive"
#endif
#if EVU_RMS_BF16_INPUT != 0 && EVU_RMS_BF16_INPUT != 1
#error "EVU_RMS_BF16_INPUT must be 0 or 1"
#endif
#if EVU_RMS_VERBOSE != 0 && EVU_RMS_VERBOSE != 1
#error "EVU_RMS_VERBOSE must be 0 or 1"
#endif

enum { ROWS = 4, COLS = 512, CHUNK = 128, ELEMENTS = ROWS * COLS,
       BLOCKS = ELEMENTS / 16, GUARD = 64 };
#define ALIGN __attribute__((aligned(64)))
static float inputs[ELEMENTS] ALIGN;
static float gamma[COLS] ALIGN;
static float constants[5] ALIGN;
static float sums[ROWS] ALIGN;
static float reciprocals[ROWS] ALIGN;
static float reference_sums[ROWS] ALIGN;
static float reference_averages[ROWS] ALIGN;
static float reference_reciprocals[ROWS] ALIGN;
static float reference_y[ELEMENTS] ALIGN;
static volatile float epsilon = EVU_RMS_EPS;
static volatile float alpha = EVU_RMS_ALPHA;
static float quant_constants[4] ALIGN;
static uint32_t quant_integers[2] ALIGN = {1, 127};
static float reference_maxima[BLOCKS] ALIGN;
static float reference_inverses[BLOCKS] ALIGN;
static uint8_t reference_scales[BLOCKS] ALIGN;
static uint8_t reference_packed[ELEMENTS / 2] ALIGN;
static struct { uint8_t before[GUARD]; uint8_t data[BLOCKS]; uint8_t after[GUARD]; } scales ALIGN;
static struct { uint8_t before[GUARD]; uint8_t data[ELEMENTS / 2]; uint8_t after[GUARD]; } packed ALIGN;
static uint64_t fence_count;
static struct { uint8_t before[GUARD]; float data[ELEMENTS]; uint8_t after[GUARD]; } normalized ALIGN;

enum Stage { EMPTY, SUMSQ, SCALE_DIV_EPS, RSQRT_EVU, RMS_EVU, QUANT_EVU, FUSED_NVFP4, STAGES };
static const char *const stage_names[STAGES] = {
  "empty", "sumsq", "scale_div_eps", "rsqrt_evu", "rms_evu", "quant_evu", "fused_nvfp4"
};

static inline uint64_t read_cycle(void) {
  uint64_t value;
  __asm__ volatile ("rdcycle %0" : "=r"(value) :: "memory");
  return value;
}

static inline uint64_t read_instret(void) {
  uint64_t value;
  __asm__ volatile ("rdinstret %0" : "=r"(value) :: "memory");
  return value;
}

static void synchronize(void) {
  XV_FENCE();
  __asm__ volatile ("fence rw, rw" ::: "memory");
  ++fence_count;
}

static inline float reference_rsqrt(float value) {
  float root, result;
  const float one = 1.0f;
  __asm__ volatile ("fsqrt.s %0, %1" : "=f"(root) : "f"(value));
  __asm__ volatile ("fdiv.s %0, %1, %2" : "=f"(result) : "f"(one), "f"(root));
  return result;
}

#define ROW_CHUNKS(operation, row, reg0, reg1, reg2, reg3) do { \
  operation(row, reg0, 0); operation(row, reg1, 1); \
  operation(row, reg2, 2); operation(row, reg3, 3); \
} while (0)

#define FOUR_ROWS(operation, chunk, reg0, reg1, reg2, reg3) do { \
  operation(0, reg0, chunk); operation(1, reg1, chunk); \
  operation(2, reg2, chunk); operation(3, reg3, chunk); \
} while (0)

#define SCHEDULE(operation, wave) do { \
  if (wave) { \
    FOUR_ROWS(operation, 0, 0, 4, 8, 12); FOUR_ROWS(operation, 1, 1, 5, 9, 13); \
    FOUR_ROWS(operation, 2, 2, 6, 10, 14); FOUR_ROWS(operation, 3, 3, 7, 11, 15); \
  } else { \
    ROW_CHUNKS(operation, 0, 0, 1, 2, 3); ROW_CHUNKS(operation, 1, 4, 5, 6, 7); \
    ROW_CHUNKS(operation, 2, 8, 9, 10, 11); ROW_CHUNKS(operation, 3, 12, 13, 14, 15); \
  } \
} while (0)

#define RN_JOIN_IMPL(prefix, index) prefix##index
#define RN_JOIN(prefix, index) RN_JOIN_IMPL(prefix, index)
#define RN_TEMP_0 20
#define RN_TEMP_1 21
#define RN_TEMP_2 22
#define RN_TEMP_3 23
#define RN_STATE_0 24
#define RN_STATE_1 25
#define RN_STATE_2 26
#define RN_STATE_3 27
#define RN_GAMMA_0 16
#define RN_GAMMA_1 17
#define RN_GAMMA_2 18
#define RN_GAMMA_3 19
#define LOAD_CHUNK(row, reg, chunk) XV_LOAD(reg, &inputs[(row) * COLS + (chunk) * CHUNK], 2)
#define STORE_CHUNK(row, reg, chunk) XV_STORE(reg, &normalized.data[(row) * COLS + (chunk) * CHUNK], 2)
#if EVU_RMS_LANE_SUM
#define RN_SQUARE_CHUNK_0(row, reg) XV_FMUL(RN_JOIN(RN_STATE_, row), reg, reg, 2)
#define RN_SQUARE_CHUNK_NEXT(row, reg) do { \
  XV_FMUL(RN_JOIN(RN_TEMP_, row), reg, reg, 2); \
  XV_FADD(RN_JOIN(RN_STATE_, row), RN_JOIN(RN_TEMP_, row), RN_JOIN(RN_STATE_, row), 2); \
} while (0)
#define RN_SQUARE_CHUNK_1(row, reg) RN_SQUARE_CHUNK_NEXT(row, reg)
#define RN_SQUARE_CHUNK_2(row, reg) RN_SQUARE_CHUNK_NEXT(row, reg)
#define RN_SQUARE_CHUNK_3(row, reg) RN_SQUARE_CHUNK_NEXT(row, reg)
#define SQUARE_CHUNK(row, reg, chunk) RN_JOIN(RN_SQUARE_CHUNK_, chunk)(row, reg)
#define RN_SUMSQ_FINAL() do { \
  XV_FREDUCE_SUM(24, 28, 24, 2); \
  XV_FREDUCE_SUM(25, 28, 25, 2); \
  XV_FREDUCE_SUM(26, 28, 26, 2); \
  XV_FREDUCE_SUM(27, 28, 27, 2); \
} while (0)
#else
#define SQUARE_CHUNK(row, reg, chunk) do { \
  XV_FMUL(RN_JOIN(RN_TEMP_, row), reg, reg, 2); \
  XV_FREDUCE_SUM(RN_JOIN(RN_STATE_, row), RN_JOIN(RN_STATE_, row), RN_JOIN(RN_TEMP_, row), 2); \
} while (0)
#define RN_SUMSQ_FINAL()
#endif
#define NORMALIZE_CHUNK(row, reg, chunk) do { \
  XV_FMUL_SCALAR(reg, RN_JOIN(RN_STATE_, row), reg); \
  XV_FMUL(reg, RN_JOIN(RN_GAMMA_, chunk), reg, 2); \
} while (0)

static void load_operands(unsigned wave) {
  __asm__ volatile ("fence rw, rw" ::: "memory");
  (void)xv_setvli(CHUNK, 0x10);
  SCHEDULE(LOAD_CHUNK, wave);
  XV_LOAD(16, &gamma[0], 2);
  XV_LOAD(17, &gamma[128], 2);
  XV_LOAD(18, &gamma[256], 2);
  XV_LOAD(19, &gamma[384], 2);
  XV_LOAD_E0(28, &constants[0]);
  XV_LOAD_E0(29, &constants[1]);
  XV_LOAD_E0(30, &constants[2]);
  XV_LOAD_E0(31, &constants[4]);
}

static void seed_sums(void) {
#if !EVU_RMS_LANE_SUM
  XV_FADD_E0(24, 28, 28);
  XV_FADD_E0(25, 28, 28);
  XV_FADD_E0(26, 28, 28);
  XV_FADD_E0(27, 28, 28);
#endif
}

static void load_row_scalars(const float *values) {
  XV_LOAD_E0(24, &values[0]);
  XV_LOAD_E0(25, &values[1]);
  XV_LOAD_E0(26, &values[2]);
  XV_LOAD_E0(27, &values[3]);
}

static void store_row_scalars(float *values) {
  XV_STORE_E0(24, &values[0]);
  XV_STORE_E0(25, &values[1]);
  XV_STORE_E0(26, &values[2]);
  XV_STORE_E0(27, &values[3]);
}

static void sum_of_squares(unsigned wave) {
  SCHEDULE(SQUARE_CHUNK, wave);
  RN_SUMSQ_FINAL();
}

#define SCALE_ROW(operation, scalar, reg) do { \
  operation(reg, scalar, reg); XV_FADD_E0(reg, 30, reg); \
} while (0)

#define SCALE_SCHEDULE(operation, scalar) do { \
  if (wave) { \
    operation(24, scalar, 24); operation(25, scalar, 25); \
    operation(26, scalar, 26); operation(27, scalar, 27); \
    XV_FADD_E0(24, 30, 24); XV_FADD_E0(25, 30, 25); \
    XV_FADD_E0(26, 30, 26); XV_FADD_E0(27, 30, 27); \
  } else { \
    SCALE_ROW(operation, scalar, 24); SCALE_ROW(operation, scalar, 25); \
    SCALE_ROW(operation, scalar, 26); SCALE_ROW(operation, scalar, 27); \
  } \
} while (0)

static void scale_epsilon(unsigned wave, unsigned divide) {
  if (divide) {
    SCALE_SCHEDULE(XV_FDIV_E0, 31);
  } else {
    SCALE_SCHEDULE(XV_FMUL_E0, 29);
  }
}

static void evu_rsqrt(unsigned wave) {
  if (wave) {
    XV_FSQRT_E0(24, 24); XV_FSQRT_E0(25, 25);
    XV_FSQRT_E0(26, 26); XV_FSQRT_E0(27, 27);
    XV_FDIV_E0(24, 24, 31); XV_FDIV_E0(25, 25, 31);
    XV_FDIV_E0(26, 26, 31); XV_FDIV_E0(27, 27, 31);
  } else {
    XV_FSQRT_E0(24, 24); XV_FDIV_E0(24, 24, 31);
    XV_FSQRT_E0(25, 25); XV_FDIV_E0(25, 25, 31);
    XV_FSQRT_E0(26, 26); XV_FDIV_E0(26, 26, 31);
    XV_FSQRT_E0(27, 27); XV_FDIV_E0(27, 27, 31);
  }
}

static void normalize(unsigned wave) {
  SCHEDULE(NORMALIZE_CHUNK, wave);
}

static void store_vectors(unsigned wave) {
  SCHEDULE(STORE_CHUNK, wave);
}

static void rmsnorm(unsigned wave) {
  seed_sums();
  sum_of_squares(wave);
  scale_epsilon(wave, 0);
  evu_rsqrt(wave);
  normalize(wave);
}

#define LOAD_REFERENCE(row, reg, chunk) XV_LOAD(reg, &reference_y[(row) * COLS + (chunk) * CHUNK], 2)

static void quantize(void) {
  RN_QUANT_INITIALIZE();
  RN_QUANT_BATCHES();
}

static void execute(enum Stage stage, unsigned wave) {
  switch (stage) {
    case EMPTY: break;
    case SUMSQ: sum_of_squares(wave); break;
    case SCALE_DIV_EPS: scale_epsilon(wave, 1); break;
    case RSQRT_EVU: evu_rsqrt(wave); break;
    case RMS_EVU: rmsnorm(wave); break;
    case QUANT_EVU: quantize(); break;
    case FUSED_NVFP4:
      load_operands(wave);
      rmsnorm(wave);
      quantize();
      break;
    default: break;
  }
}

static void initialize(void) {
  constants[0] = 0.0f;
  constants[1] = 1.0f / 512.0f;
  constants[2] = epsilon;
  constants[3] = 512.0f;
  constants[4] = 1.0f;
  quant_constants[0] = 6.0f;
  quant_constants[1] = alpha;
  quant_constants[2] = 1.0f;
  quant_constants[3] = 1.0f / 512.0f;
  for (unsigned col = 0; col < COLS; ++col) {
    float value = 0.5f + (float)((col * 13u) % 101u) / 128.0f;
    gamma[col] = EVU_RMS_BF16_INPUT ? rn_bf16_round(value) : value;
  }
  for (unsigned row = 0; row < ROWS; ++row) {
    float sum = 0.0f;
    for (unsigned col = 0; col < COLS; ++col) {
      float value = (float)((int)((row * 113u + col * 37u) % 509u) - 254) / 97.0f;
      if (row == 0 && col < 16) value = 0.0f;
      if (EVU_RMS_BF16_INPUT) value = rn_bf16_round(value);
      inputs[row * COLS + col] = value;
      sum += value * value;
    }
    reference_sums[row] = sum;
    reference_averages[row] = sum * (1.0f / 512.0f) + epsilon;
    reference_reciprocals[row] = reference_rsqrt(reference_averages[row]);
    for (unsigned col = 0; col < COLS; ++col)
      reference_y[row * COLS + col] =
        (inputs[row * COLS + col] * reference_reciprocals[row]) * gamma[col];
  }
  memset(normalized.before, 0xa5, GUARD);
  memset(normalized.after, 0x5a, GUARD);
  memset(scales.before, 0xa5, GUARD);
  memset(scales.after, 0x5a, GUARD);
  memset(packed.before, 0xa5, GUARD);
  memset(packed.after, 0x5a, GUARD);
}

static void prepare(enum Stage stage, unsigned wave) {
  memset(normalized.data, 0xcc, sizeof(normalized.data));
  memset(scales.data, 0xcc, sizeof(scales.data));
  memset(packed.data, 0xcc, sizeof(packed.data));
  if (stage == QUANT_EVU) {
    (void)xv_setvli(CHUNK, 0x10);
    SCHEDULE(LOAD_REFERENCE, wave);
  }
  if (stage == SUMSQ || stage == SCALE_DIV_EPS ||
      stage == RSQRT_EVU || stage == RMS_EVU) {
    load_operands(wave);
    if (stage == SUMSQ) seed_sums();
    if (stage == SCALE_DIV_EPS) load_row_scalars(reference_sums);
    if (stage == RSQRT_EVU) load_row_scalars(reference_averages);
    if (stage == SCALE_DIV_EPS) XV_LOAD_E0(31, &constants[3]);
  }
  synchronize();
  XV_CSR_WRITE(XV_EFFLAGS, 0);
  __asm__ volatile ("csrw fflags, x0" ::: "memory");
}

static unsigned compare_floats(const float *actual, const float *expected, unsigned count) {
  unsigned errors = 0;
  for (unsigned element = 0; element < count; ++element) {
    const float difference = rn_abs(actual[element] - expected[element]);
    errors += !(difference <= 2.0e-5f * (1.0f + rn_abs(expected[element])));
  }
  return errors;
}

static unsigned check_guards(const uint8_t *before, const uint8_t *after) {
  unsigned errors = 0;
  for (unsigned index = 0; index < GUARD; ++index)
    errors += before[index] != 0xa5 || after[index] != 0x5a;
  return errors;
}

static unsigned check(enum Stage stage, unsigned wave) {
  unsigned errors = 0;
  if (stage == SUMSQ || stage == SCALE_DIV_EPS) {
    store_row_scalars(sums);
    synchronize();
    errors += compare_floats(sums, stage == SUMSQ ? reference_sums : reference_averages, ROWS);
  }
  if (stage == RMS_EVU) {
    store_vectors(wave);
    synchronize();
    errors += compare_floats(normalized.data, reference_y, ELEMENTS);
  }
  if (stage == RSQRT_EVU || stage == RMS_EVU) {
    store_row_scalars(reciprocals);
    synchronize();
    errors += compare_floats(reciprocals, reference_reciprocals, ROWS);
  }
  if (stage >= QUANT_EVU) {
    const float *quant_input = reference_y;
    if (stage == FUSED_NVFP4) {
      load_operands(wave);
      rmsnorm(wave);
      store_vectors(wave);
      synchronize();
      errors += compare_floats(normalized.data, reference_y, ELEMENTS);
      quant_input = normalized.data;
    }
    rn_block_amax(quant_input, reference_maxima, BLOCKS);
    rn_block_scales(reference_maxima, alpha, reference_scales, reference_inverses, BLOCKS);
    rn_pack_fp4(quant_input, reference_inverses, reference_packed, BLOCKS);
    errors += memcmp(scales.data, reference_scales, sizeof(reference_scales)) != 0;
    errors += memcmp(packed.data, reference_packed, sizeof(reference_packed)) != 0;
  }
  errors += check_guards(normalized.before, normalized.after);
  errors += check_guards(scales.before, scales.after);
  errors += check_guards(packed.before, packed.after);
  errors += !!(XV_CSR_READ(XV_EVSTATUS) & 2);
  errors += !!(XV_CSR_READ(XV_EFFLAGS) & (stage >= QUANT_EVU ? 0x18 : 0x1c));
  errors += !!(XV_CSR_READ(0x001) & 0x1c);
  return errors;
}

static unsigned schedule_count(enum Stage stage) {
  return stage == EMPTY || stage == SCALE_DIV_EPS || stage == QUANT_EVU ? 1 : 2;
}

static void sort_samples(uint64_t *values) {
  for (unsigned index = 1; index < EVU_RMS_SAMPLES; ++index) {
    const uint64_t value = values[index];
    unsigned slot = index;
    while (slot && values[slot - 1] > value) {
      values[slot] = values[slot - 1];
      --slot;
    }
    values[slot] = value;
  }
}

static uint64_t sample_median(const uint64_t *values) {
  const uint64_t lower = values[(EVU_RMS_SAMPLES - 1) / 2];
  return lower + (values[EVU_RMS_SAMPLES / 2] - lower) / 2;
}

#ifndef EVU_RMS_KERNELS_ONLY
__attribute__((used, noinline)) static int rmsnorm_nvfp4_test_main(void) {
  __asm__ volatile ("csrw frm, x0" ::: "memory");
  const uint64_t required = XV_CAP_RETURNED_VL | XV_CAP_RVV_MATH |
      (EVU_RMS_ONLY ? 0 : XV_CAP_LOW_PRECISION | XV_CAP_GATHER_IMM);
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
  const unsigned stage_count = EVU_RMS_ONLY ? QUANT_EVU : STAGES;
  unsigned cases = 0;
  for (enum Stage stage = EMPTY; (unsigned)stage < stage_count; ++stage) cases += schedule_count(stage);
  printf("[RN] config rows=4 cols=512 samples=%u input=%s alpha_bits=%08x eps_bits=%08x warmup=0 cases=%u format=11 quant_batch=16 quant_vl=16 quant_sew=32 quant_schedule=overlap lane_sum=%u quant_rename=%u quant_banked=%u workload=%s quantization=%s\n",
    (unsigned)EVU_RMS_SAMPLES, EVU_RMS_BF16_INPUT ? "bf16_rounded_fp32" : "fp32",
    rn_float_bits(alpha), rn_float_bits(epsilon), cases,
    (unsigned)EVU_RMS_LANE_SUM, (unsigned)EVU_RMS_QUANT_RENAME,
    (unsigned)EVU_RMS_QUANT_BANKED,
    EVU_RMS_ONLY ? "rmsnorm_evu" : "rmsnorm_nvfp4_evu", EVU_RMS_ONLY ? "not_measured" : "evu");
  for (enum Stage stage = EMPTY; (unsigned)stage < stage_count; ++stage) {
    const unsigned schedules = schedule_count(stage);
    for (unsigned wave = 0; wave < schedules; ++wave) {
      const char *schedule = stage != EMPTY ? (wave ? "wave4" : "row_major") : "overhead";
      uint64_t cycles[EVU_RMS_SAMPLES], retired[EVU_RMS_SAMPLES];
      for (unsigned sample = 0; sample < EVU_RMS_SAMPLES; ++sample) {
        prepare(stage, wave);
        const uint64_t start_fence = fence_count - 1;
        const uint64_t start_instructions = read_instret();
        const uint64_t start = read_cycle();
        execute(stage, wave);
        synchronize();
        const uint64_t end = read_cycle();
        const uint64_t instructions = read_instret() - start_instructions;
        const uint64_t end_fence = fence_count - 1;
        const unsigned errors = check(stage, wave) + (end <= start);
        cycles[sample] = end - start;
        retired[sample] = instructions;
        if (EVU_RMS_VERBOSE || errors)
          printf("[RN-SAMPLE] stage=%s schedule=%s sample=%u cycles=%lu instructions=%lu errors=%u fences=%lu:%lu\n",
            stage_names[stage], schedule, sample, (unsigned long)cycles[sample],
            (unsigned long)instructions, errors, (unsigned long)start_fence, (unsigned long)end_fence);
        if (errors) {
          printf("[RN] FAIL stage=%s schedule=%s sample=%u\n", stage_names[stage], schedule, sample);
          return 1;
        }
      }
      if (EVU_RMS_SAMPLES == 1) {
        printf("[RN] stage=%s schedule=%s cycles=%lu inst=%lu errors=0\n",
          stage_names[stage], schedule, (unsigned long)cycles[0], (unsigned long)retired[0]);
      } else {
        sort_samples(cycles);
        sort_samples(retired);
        printf("[RN] stage=%s schedule=%s n=%u min=%lu median=%lu max=%lu inst=%lu errors=0\n",
          stage_names[stage], schedule, (unsigned)EVU_RMS_SAMPLES, (unsigned long)cycles[0],
          (unsigned long)sample_median(cycles), (unsigned long)cycles[EVU_RMS_SAMPLES - 1],
          (unsigned long)sample_median(retired));
      }
    }
  }
  printf("[RN] PASS\n");
  return 0;
}

__attribute__((naked)) int main(void) {
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail rmsnorm_nvfp4_test_main\n");
}
#endif
