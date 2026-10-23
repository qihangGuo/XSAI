#include <am.h>
#include <klib.h>
#include <stdint.h>
#include "evu_intrinsics.h"

/* DefaultExternalVectorMatrixConfig: VLEN=4096, FP32, full VL, unmasked.
 * All tensor arithmetic and reductions run on EVU; scalar FP is the checker.
 */
enum { ELEMENTS = 128, BLOCKS = 4, GUARD_BYTES = 64, CASES = 7, CONSTANTS = 3 };

union VectorData {
  float f32[ELEMENTS];
  uint32_t u32[ELEMENTS];
};

struct GuardedVector {
  uint8_t before[GUARD_BYTES];
  union VectorData data;
  uint8_t after[GUARD_BYTES];
} __attribute__((aligned(64)));

static volatile struct GuardedVector input[BLOCKS], output[BLOCKS];
static volatile struct GuardedVector exponent[BLOCKS], maximum[BLOCKS], denominator[BLOCKS];
static volatile struct GuardedVector constants[CONSTANTS];
static volatile float shift_reference[BLOCKS][ELEMENTS];
static const char *const case_names[CASES] = {
  "uniform", "rising-max", "falling-max", "alternating-max",
  "positive-shift", "negative-shift", "single-peak"
};

static uint32_t float_bits(float value)
{
  union { float f; uint32_t u; } bits = { .f = value };
  return bits.u;
}

static float input_value(unsigned test, unsigned block, unsigned element)
{
  const float fraction = (float)((int)((element * 37u + block * 11u) % 65u) - 32) * 0.0625f;
  if (test == 0) return 0.0f;
  if (test == 2) return (float)((BLOCKS - 1u - block) * 4u) + fraction;
  if (test == 3) {
    static const int offsets[BLOCKS] = {0, 8, 3, 12};
    return (float)offsets[block] + fraction;
  }
  if (test == 6) {
    if (block == 2 && element == 101) return 16.0f;
    return -16.0f - (float)(element % 23u) * 0.25f;
  }
  const float shift = test == 4 ? 64.0f : test == 5 ? -64.0f : 0.0f;
  return (float)(block * 3u) + fraction + shift;
}

static void initialize_buffer(volatile struct GuardedVector *buffer)
{
  for (unsigned i = 0; i < GUARD_BYTES; ++i) {
    buffer->before[i] = 0xa5;
    buffer->after[i] = 0x5a;
  }
  for (unsigned i = 0; i < ELEMENTS; ++i) buffer->data.u32[i] = 0x7fc0dead;
}

static void initialize(unsigned test)
{
  for (unsigned b = 0; b < BLOCKS; ++b) {
    initialize_buffer(&input[b]);
    initialize_buffer(&output[b]);
    initialize_buffer(&exponent[b]);
    initialize_buffer(&maximum[b]);
    initialize_buffer(&denominator[b]);
    for (unsigned i = 0; i < ELEMENTS; ++i) input[b].data.f32[i] = input_value(test, b, i);
  }
  for (unsigned c = 0; c < CONSTANTS; ++c) initialize_buffer(&constants[c]);
  for (unsigned i = 0; i < ELEMENTS; ++i) {
    constants[0].data.u32[i] = 0xff7fffff; /* -FLT_MAX, max-reduction seed. */
    constants[1].data.u32[i] = 0; /* Both FP +0 and gather index zero. */
    constants[2].data.f32[i] = 1.4426950408889634f;
  }
}

/* Independent e^x reference for these nonpositive, bounded inputs. Reduce to
 * [-ln(2), 0], evaluate a degree-16 Taylor polynomial, then scale by exact 2^n.
 * No libm or EVU result is used to construct the expected probabilities.
 */
static double reference_exp(double x)
{
  static const double coefficient[] = {
    1.0, 1.0, 0.5, 1.0 / 6.0, 1.0 / 24.0, 1.0 / 120.0,
    1.0 / 720.0, 1.0 / 5040.0, 1.0 / 40320.0, 1.0 / 362880.0,
    1.0 / 3628800.0, 1.0 / 39916800.0, 1.0 / 479001600.0,
    1.0 / 6227020800.0, 1.0 / 87178291200.0,
    1.0 / 1307674368000.0, 1.0 / 20922789888000.0
  };
  const int n = (int)(x * 1.4426950408889634074);
  const double r = x - (double)n * 0.69314718055994530942;
  double value = coefficient[16];
  for (int k = 15; k >= 0; --k) value = coefficient[k] + r * value;
  union { uint64_t u; double d; } scale = { .u = (uint64_t)(n + 1023) << 52 };
  return value * scale.d;
}

static int check_near(unsigned test, const char *stage, unsigned block, unsigned element,
                      float actual, double expected, double absolute, double relative)
{
  double delta = (double)actual - expected;
  if (delta < 0.0) delta = -delta;
  const double magnitude = expected < 0.0 ? -expected : expected;
  if ((float_bits(actual) & 0x7f800000u) == 0x7f800000u || !(delta <= absolute + relative * magnitude)) {
    printf("EVU online softmax FAIL: case=%u stage=%s block=%u element=%u expected=0x%08x actual=0x%08x\n",
      test, stage, block, element, float_bits((float)expected), float_bits(actual));
    return 1;
  }
  return 0;
}

static void synchronize(void)
{
  XV_FENCE();
  __asm__ volatile ("fence rw, rw" ::: "memory");
}

/* v17/v18 hold broadcast running max/sum. v28 is -FLT_MAX, v29 is zero,
 * v30 is log2(e). A reduction writes only element zero; GATHER broadcasts it.
 */
static void update_block(unsigned block)
{
  XV_LOAD(1, &input[block].data, 2);
  XV_FREDUCE_MAX(2, 28, 1, 2);
  XV_GATHER(3, 29, 2, 2);
  if (block != 0) {
    XV_FMAX(3, 17, 3, 2);
    XV_FSUB(4, 3, 17, 2); /* old_max - new_max */
    XV_FMUL(4, 30, 4, 2);
    XV_EXP2(4, 4, 2);
    XV_FMUL(18, 4, 18, 2); /* old_sum * exp(old_max - new_max) */
  }
  XV_FSUB(5, 3, 1, 2); /* input - new_max */
  XV_FMUL(5, 30, 5, 2);
  XV_EXP2(5, 5, 2);
  XV_STORE(5, &exponent[block].data, 2);
  XV_FREDUCE_SUM(6, 29, 5, 2);
  XV_GATHER(7, 29, 6, 2);
  if (block == 0) {
    XV_FMAX(18, 7, 7, 2);
  } else {
    XV_FADD(18, 7, 18, 2);
  }
  XV_FMAX(17, 3, 3, 2);
  XV_STORE(17, &maximum[block].data, 2);
  XV_STORE(18, &denominator[block].data, 2);
  synchronize();
}

static int check_block(unsigned test, unsigned block, double *running_max, double *running_sum)
{
  double block_max = input_value(test, block, 0);
  for (unsigned i = 1; i < ELEMENTS; ++i) {
    const double x = input_value(test, block, i);
    if (x > block_max) block_max = x;
  }
  double new_max = block_max;
  double new_sum = 0.0;
  if (block != 0) {
    if (*running_max > new_max) new_max = *running_max;
    new_sum = *running_sum * reference_exp(*running_max - new_max);
  }
  for (unsigned i = 0; i < ELEMENTS; ++i) {
    const double expected_exp = reference_exp((double)input_value(test, block, i) - new_max);
    new_sum += expected_exp;
    if (check_near(test, "exp", block, i, exponent[block].data.f32[i], expected_exp, 2e-8, 2e-5)) return 1;
  }
  for (unsigned i = 0; i < ELEMENTS; ++i) {
    if (check_near(test, "max", block, i, maximum[block].data.f32[i], new_max, 0.0, 0.0)) return 1;
    if (check_near(test, "sum", block, i, denominator[block].data.f32[i], new_sum, 1e-5, 3e-5)) return 1;
  }
  *running_max = new_max;
  *running_sum = new_sum;
  return 0;
}

static void normalize(void)
{
  /* Revisit all blocks with the final max/sum; early block probabilities
   * must change when a later block introduces a larger maximum.
   */
  for (unsigned b = 0; b < BLOCKS; ++b) {
    XV_LOAD(1, &input[b].data, 2);
    XV_FSUB(5, 17, 1, 2);
    XV_FMUL(5, 30, 5, 2);
    XV_EXP2(31, 5, 2);
    XV_FDIV(31, 18, 31, 2); /* exp(input - final_max) / final_sum */
    XV_STORE(31, &output[b].data, 2);
  }
  synchronize();
}

static int check_output(unsigned test)
{
  /* Offline, whole-row reference is independent of the DUT's online state. */
  double max = input_value(test, 0, 0);
  for (unsigned b = 0; b < BLOCKS; ++b) {
    for (unsigned i = 0; i < ELEMENTS; ++i) {
      const double x = input_value(test, b, i);
      if (x > max) max = x;
    }
  }
  double total = 0.0;
  for (unsigned b = 0; b < BLOCKS; ++b) {
    for (unsigned i = 0; i < ELEMENTS; ++i) total += reference_exp((double)input_value(test, b, i) - max);
  }
  double probability_sum = 0.0;
  for (unsigned b = 0; b < BLOCKS; ++b) {
    for (unsigned i = 0; i < ELEMENTS; ++i) {
      const float actual = output[b].data.f32[i];
      const double expected = reference_exp((double)input_value(test, b, i) - max) / total;
      if (check_near(test, "output", b, i, actual, expected, 2e-8, 5e-5)) return 1;
      if (!(actual > 0.0f && actual <= 1.0f)) {
        printf("EVU online softmax FAIL: case=%u stage=range block=%u element=%u actual=0x%08x\n",
          test, b, i, float_bits(actual));
        return 1;
      }
      probability_sum += (double)actual;
      if (test == 1) shift_reference[b][i] = actual;
      if (test == 4 || test == 5) {
        if (check_near(test, "shift-invariance", b, i, actual, shift_reference[b][i], 2e-8, 5e-5)) return 1;
      }
    }
  }
  return check_near(test, "probability-sum", 0, 0, (float)probability_sum, 1.0, 2e-4, 0.0);
}

static int check_guard(unsigned test, const char *name, unsigned block,
                       const volatile struct GuardedVector *buffer)
{
  for (unsigned i = 0; i < GUARD_BYTES; ++i) {
    if (buffer->before[i] != 0xa5 || buffer->after[i] != 0x5a) {
      printf("EVU online softmax FAIL: case=%u stage=guard buffer=%s block=%u byte=%u\n",
        test, name, block, i);
      return 1;
    }
  }
  return 0;
}

static int check_buffers(unsigned test)
{
  for (unsigned b = 0; b < BLOCKS; ++b) {
    if (check_guard(test, "input", b, &input[b]) || check_guard(test, "output", b, &output[b]) ||
        check_guard(test, "exp", b, &exponent[b]) || check_guard(test, "max", b, &maximum[b]) ||
        check_guard(test, "sum", b, &denominator[b])) return 1;
    for (unsigned i = 0; i < ELEMENTS; ++i) {
      const uint32_t expected = float_bits(input_value(test, b, i));
      if (input[b].data.u32[i] != expected) {
        printf("EVU online softmax FAIL: case=%u stage=input-preservation block=%u element=%u\n", test, b, i);
        return 1;
      }
    }
  }
  const uint32_t expected_constants[CONSTANTS] = {0xff7fffff, 0, float_bits(1.4426950408889634f)};
  for (unsigned c = 0; c < CONSTANTS; ++c) {
    if (check_guard(test, "constant", c, &constants[c])) return 1;
    for (unsigned i = 0; i < ELEMENTS; ++i) {
      if (constants[c].data.u32[i] != expected_constants[c]) {
        printf("EVU online softmax FAIL: case=%u stage=constant-preservation buffer=%u element=%u\n", test, c, i);
        return 1;
      }
    }
  }
  return 0;
}

static int online_softmax_test_main(void) __attribute__((used, noinline));
static int online_softmax_test_main(void)
{
  for (unsigned test = 0; test < CASES; ++test) {
    initialize(test);
    __asm__ volatile ("fence rw, rw" ::: "memory");
    XV_LOAD(28, &constants[0].data, 2);
    XV_LOAD(29, &constants[1].data, 2);
    XV_LOAD(30, &constants[2].data, 2);
    double running_max = 0.0, running_sum = 0.0;
    for (unsigned b = 0; b < BLOCKS; ++b) {
      update_block(b);
      if (check_block(test, b, &running_max, &running_sum)) return 1;
    }
    normalize();
    if (check_output(test) || check_buffers(test)) return 1;
    printf("EVU online softmax PASS: case=%u %s elements=%u blocks=%u\n",
      test, case_names[test], (unsigned)(BLOCKS * ELEMENTS), (unsigned)BLOCKS);
  }
  printf("EVU online softmax PASS: FP32, exp2, online max/sum, normalization and guards; no matrix operations\n");
  return 0;
}

int main(void) __attribute__((naked));
int main(void)
{
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail online_softmax_test_main\n");
}
