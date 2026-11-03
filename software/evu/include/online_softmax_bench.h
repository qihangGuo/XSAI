#ifndef ONLINE_SOFTMAX_BENCH_H
#define ONLINE_SOFTMAX_BENCH_H

#include <klib.h>
#include <stdint.h>

#if defined(__FAST_MATH__)
#error "online softmax comparisons require -fno-fast-math -ffp-contract=off"
#endif

/* Shared workload and accuracy contract; neither backend supplies its own oracle. */
static uint32_t float_bits(float value) {
  union { float f; uint32_t u; } bits = { .f = value };
  return bits.u;
}

static float input_value(unsigned row, unsigned col) {
  static const float pattern[] = {
    -4.0f, -2.5f, -1.0f, -0.25f, 0.0f, 0.5f, 1.25f, 2.0f,
    3.0f, 3.5f, -3.0f, 1.75f, -1.5f, 0.25f, 2.5f, -0.75f
  };
  return pattern[(col + row * 3) % 16] +
    (float)((int)(col % 11) - 5) * 0.00390625f + (float)row * 0.015625f;
}

static float initial_max(unsigned row) { return 2.0f + (float)row * 0.0625f; }
static float initial_sum(unsigned row) { return 8.0f + (float)row; }

#if ONLINE_SOFTMAX_CHECK
/* Independent FP64 oracle. Split x into an integer and a fraction on the
 * negative side, then use two small tables built with a degree-16 polynomial.
 * No DUT result or FP32 exp2 approximation is used by the reference. */
static double exp_fraction[257], exp_integer[701];
static void initialize_reference(void) {
  static const double coefficient[] = {
    1.0, 1.0, 0.5, 1.0 / 6.0, 1.0 / 24.0, 1.0 / 120.0,
    1.0 / 720.0, 1.0 / 5040.0, 1.0 / 40320.0, 1.0 / 362880.0,
    1.0 / 3628800.0, 1.0 / 39916800.0, 1.0 / 479001600.0,
    1.0 / 6227020800.0, 1.0 / 87178291200.0,
    1.0 / 1307674368000.0, 1.0 / 20922789888000.0
  };
  for (unsigned i = 0; i <= 256; ++i) {
    const double x = -(double)i / 256.0;
    double value = coefficient[16];
    for (int k = 15; k >= 0; --k) value = coefficient[k] + x * value;
    exp_fraction[i] = value;
  }
  exp_integer[0] = 1.0;
  for (unsigned i = 1; i <= 700; ++i)
    exp_integer[i] = exp_integer[i - 1] * 0.36787944117144232160;
}

static double reference_exp(double x) {
  if (x != x) return x;
  if (x > 0.0) return 1.0 / reference_exp(-x);
  if (x < -700.0) return 0.0;
  const unsigned integer = (unsigned)(-x);
  const double fraction = -x - integer;
  const unsigned index = (unsigned)(fraction * 256.0);
  const double residual = -(fraction - (double)index / 256.0);
  /* The residual magnitude is below 1/256; a sixth-degree Taylor expansion
   * is more accurate than the FP32 DUT throughout this interval. */
  double value = 1.0;
  if (residual != 0.0) {
    value = 1.0 / 720.0;
    static const double coefficient[] = { 1.0, 1.0, 0.5, 1.0 / 6.0, 1.0 / 24.0, 1.0 / 120.0 };
    for (int k = 5; k >= 0; --k) value = coefficient[k] + residual * value;
  }
  return exp_integer[integer] * exp_fraction[index] * value;
}

static double absolute(double value) { return value < 0.0 ? -value : value; }

static unsigned check_near(unsigned first, const char *what, unsigned row,
                            unsigned col, float actual, double expected,
                            double atol, double rtol, unsigned reported) {
  if ((float_bits(actual) & 0x7f800000u) != 0x7f800000u &&
      absolute((double)actual - expected) <= atol + rtol * absolute(expected)) return 0;
  if (reported < 4)
    printf("[OS] mismatch first=%u %s r=%u c=%u got=%08x ref=%08x\n",
      first, what, row, col, float_bits(actual), float_bits((float)expected));
  return 1;
}

static unsigned online_softmax_validate(unsigned first, const float *output,
    const float *maxima, const float *sums, const float *alphas, float scale,
    unsigned rows, unsigned cols, unsigned stride,
    double *score_nmse, double *state_nmse) {
  double score_error = 0.0, score_energy = 0.0, state_error = 0.0, state_energy = 0.0;
  unsigned failures = 0;
  for (unsigned row = 0; row < rows; ++row) {
    float raw_max = input_value(row, 0);
    for (unsigned col = 1; col < cols; ++col) {
      const float value = input_value(row, col);
      if (value > raw_max) raw_max = value;
    }
    float max = scale == 1.0f ? raw_max : raw_max * scale;
    if (!first && initial_max(row) > max) max = initial_max(row);
    const double alpha = first ? 0.0 : reference_exp((double)initial_max(row) - max);
    double sum = 0.0;
    for (unsigned col = 0; col < cols; ++col) {
      const double expected = reference_exp((double)input_value(row, col) * scale - max);
      const float actual = output[row * stride + col];
      sum += expected;
      const double delta = (double)actual - expected;
      score_error += delta * delta;
      score_energy += expected * expected;
      failures += check_near(first, "exp", row, col, actual, expected, 2e-8, 2e-5, failures);
    }
    /* Separate FP32 multiply/add in the DUT; the oracle evaluates in FP64. */
    sum += alpha * initial_sum(row);
    failures += check_near(first, "max", row, 0, maxima[row], max, 0.0, 0.0, failures);
    failures += check_near(first, "sum", row, 0, sums[row], sum, 1e-5, 3e-5, failures);
    failures += check_near(first, "alpha", row, 0, alphas[row], alpha, 2e-8, 2e-5, failures);
    const double expected_state[] = { max, sum, alpha };
    const float actual_state[] = { maxima[row], sums[row], alphas[row] };
    for (unsigned i = 0; i < 3; ++i) {
      const double delta = (double)actual_state[i] - expected_state[i];
      state_error += delta * delta;
      state_energy += expected_state[i] * expected_state[i];
    }
    for (unsigned col = cols; col < stride; ++col)
      if (float_bits(output[row * stride + col]) != 0) ++failures;
  }
  *score_nmse = score_energy > 0.0 ? score_error / score_energy : score_error;
  *state_nmse = state_energy > 0.0 ? state_error / state_energy : state_error;
  if (!(*score_nmse <= 1e-5) || !(*state_nmse <= 1e-5)) ++failures;
  return failures;
}
#endif

#endif
