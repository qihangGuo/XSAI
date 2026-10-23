#include "evu_intrinsics.h"
#include "ame_intrinsics.h"

#include <stdint.h>

/*
 * Fixed-size CUTE-to-EVU correctness test:
 *
 *   M=N=128, K=64, B is already INT8-quantized.
 *
 * The arrays deliberately use a simple periodic pattern.  This keeps the
 * expected result exactly representable while exercising the complete
 * CUTE -> EVU FP32 dequantization -> HBL2 store path.
 * Run unit scales first, then distinct per-row/per-column scales to check
 * parameter indexing and repeated launches.
 */
#define Q_M 128
#define Q_N 128
#define Q_K 64

static int8_t q_a[Q_M * Q_K] __attribute__((aligned(64)));
static int8_t q_b_t[Q_N * Q_K] __attribute__((aligned(64)));
static float q_scale_a[Q_M] __attribute__((aligned(64)));
static float q_scale_b[Q_N] __attribute__((aligned(64)));
static volatile float q_output[Q_N * Q_M] __attribute__((aligned(64)));

static inline uint64_t q_read_cycle(void)
{
  uint64_t value;
  __asm__ volatile ("rdcycle %0" : "=r"(value) :: "memory");
  return value;
}

static float q_a_scale(int m, int varied) { return varied ? 0.5f + (m % 7) * 0.25f : 1.0f; }
static float q_b_scale(int n, int varied) { return varied ? 0.25f + (n % 11) * 0.125f : 1.0f; }

static void q_init(int varied)
{
  for (int m = 0; m < Q_M; ++m) {
    for (int k = 0; k < Q_K; ++k) {
      q_a[m * Q_K + k] = (int8_t)((m % 5) - 2);
    }
    q_scale_a[m] = q_a_scale(m, varied);
  }
  for (int n = 0; n < Q_N; ++n) {
    for (int k = 0; k < Q_K; ++k) {
      /* CUTE receives B as [N,K] and the public result is [N,M]. */
      q_b_t[n * Q_K + k] = (int8_t)((n % 3) - 1);
    }
    q_scale_b[n] = q_b_scale(n, varied);
  }
  for (int i = 0; i < Q_N * Q_M; ++i) q_output[i] = -123.0f;
}

static int q_check(int varied)
{
  for (int n = 0; n < Q_N; ++n) {
    const int8_t b = (int8_t)((n % 3) - 1);
    for (int m = 0; m < Q_M; ++m) {
      const int8_t a = (int8_t)((m % 5) - 2);
      const float expected = ((float)(Q_K * (int)a * (int)b) * q_a_scale(m, varied)) * q_b_scale(n, varied);
      float delta = q_output[n * Q_M + m] - expected;
      if (delta < 0.0f) delta = -delta;
      if (!(delta <= 0.01f)) return 1;
    }
  }
  return 0;
}

/*
 * The helper functions are useful when this file is included by a larger
 * benchmark. The producer's mrelease/macquire pair precedes the terminal
 * xv.fence, which drains the CUTE stream and the final HBL2 write response.
 */
void xsai_evu_quanti8_begin(const float *scale_a, const float *scale_b, float *output)
{
  XV_LOAD(1, scale_a, 2);
  XV_LOAD(2, scale_b, 2);
  XV_LAUNCH_FP32(output, 1, 2);
}

void xsai_evu_quanti8_end(void)
{
  XV_WAIT();
  XV_FENCE();
}


/* Direct M=N=128, K=64 CUTE-to-EVU path with no INT32 MSCE32. */
void xsai_evu_quanti8_mma_128x64x128(
    const int8_t *a, const int8_t *b_transposed,
    const float *scale_a, const float *scale_b, float *output)
{
  const long m = 128;
  const long n = 128;
  const long k = 64;
  const long acquire_target = 1;

  MSYNCRESET();
  AME_SET_I8();
  volatile int tile;
  MSETTILEM(tile, n);
  MSETTILEK(tile, k);
  MSETTILEN(tile, m);
  (void)tile;

  XV_LOAD(1, scale_a, 2);
  XV_LOAD(2, scale_b, 2);
  XV_LAUNCH_FP32(output, 1, 2);

  MZERO_ACC(acc0);
  __asm__ volatile ("fence rw, rw" ::: "memory");
  MLAE8(tr0, b_transposed, k);
  MLBE8(tr1, a, k);
  MQMA(acc0, tr0, tr1);
  MRELEASE();
  MACQUIRE(acquire_target);

  XV_WAIT();
  XV_FENCE();
}

__attribute__((used, noinline))
static int quanti8mma_test_main(void)
{
  /* Keep timing visible to a debugger/perf dump without requiring libc I/O. */
  static volatile uint64_t measured_cycles[2];
  for (int varied = 0; varied < 2; ++varied) {
    q_init(varied);
    const uint64_t start = q_read_cycle();
    xsai_evu_quanti8_mma_128x64x128(
        q_a, q_b_t, q_scale_a, q_scale_b, (float *)q_output);
    measured_cycles[varied] = q_read_cycle() - start;
    if (q_check(varied)) return 1;
  }
  (void)measured_cycles;
  return 0;
}

/* Enable FS/VS before the compiler can emit vector or FP instructions. */
__attribute__((naked))
int main(void)
{
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail quanti8mma_test_main\n");
}
