#include <stdint.h>
#ifdef EVU_FLASH_NATIVE
#include "host/flash_attention_nemu_backend.h"
#define FA_MEMORY_FENCE() do {} while (0)
#else
#include <klib.h>
#include "ame_intrinsics.h"
#include "evu_csr.h"
#define FA_MEMORY_FENCE() __asm__ volatile ("fence rw, rw" ::: "memory")
#endif

/* Same quantized, noncausal single-head workload as the optimized RVV LMUL8
 * baseline: Q=128, KV=256, D=64. CUTE always computes a full 128x128x64 tile;
 * unused PV output features are zero padded. No core RVV arithmetic is used.
 * All vector state is EVU-local. Matrices cross the CUTE<->EVU direct ports.
 */
enum { Q = 128, KV = 256, D = 64, TILE = 128, CHUNK = 64 };
#ifndef EVU_FLASH_ROW_BATCH
#define EVU_FLASH_ROW_BATCH 4
#endif
#if EVU_FLASH_ROW_BATCH != 1 && EVU_FLASH_ROW_BATCH != 2 && EVU_FLASH_ROW_BATCH != 4
#error "EVU_FLASH_ROW_BATCH must be 1, 2 or 4"
#endif
#ifndef EVU_FLASH_MERGE_PV
#define EVU_FLASH_MERGE_PV 1
#endif
#if EVU_FLASH_MERGE_PV != 0 && EVU_FLASH_MERGE_PV != 1
#error "EVU_FLASH_MERGE_PV must be 0 or 1"
#endif
#ifndef EVU_FLASH_SCALAR_OPS
#ifdef EVU_FLASH_NATIVE
#define EVU_FLASH_SCALAR_OPS 0
#else
#define EVU_FLASH_SCALAR_OPS 1
#endif
#endif
#if EVU_FLASH_SCALAR_OPS != 0 && EVU_FLASH_SCALAR_OPS != 1
#error "EVU_FLASH_SCALAR_OPS must be 0 or 1"
#endif
#ifndef EVU_FLASH_FUSE_ALPHA
#define EVU_FLASH_FUSE_ALPHA EVU_FLASH_SCALAR_OPS
#endif
#ifndef EVU_FLASH_EARLY_RECIP
#define EVU_FLASH_EARLY_RECIP EVU_FLASH_SCALAR_OPS
#endif
#ifndef EVU_FLASH_CHUNK_MAX
#define EVU_FLASH_CHUNK_MAX EVU_FLASH_SCALAR_OPS
#endif
#if (EVU_FLASH_FUSE_ALPHA != 0 && EVU_FLASH_FUSE_ALPHA != 1) || \
    (EVU_FLASH_EARLY_RECIP != 0 && EVU_FLASH_EARLY_RECIP != 1) || \
    (EVU_FLASH_CHUNK_MAX != 0 && EVU_FLASH_CHUNK_MAX != 1)
#error "EVU_FLASH_FUSE_ALPHA, EVU_FLASH_EARLY_RECIP and EVU_FLASH_CHUNK_MAX must be 0 or 1"
#endif
#if (EVU_FLASH_FUSE_ALPHA || EVU_FLASH_EARLY_RECIP || EVU_FLASH_CHUNK_MAX) && !EVU_FLASH_SCALAR_OPS
#error "The new schedules require instruction-local scalar operations"
#endif
#if EVU_FLASH_SCALAR_OPS
#define FA_KERNEL_NAME "scalar_batched_v4"
#define FA_LOAD_E0(vd, addr) XV_LOAD_E0(vd, addr)
#define FA_STORE_E0(vd, addr) XV_STORE_E0(vd, addr)
#define FA_FADD_E0(vd, a, b) XV_FADD_E0(vd, a, b)
#define FA_FMUL_E0(vd, a, b) XV_FMUL_E0(vd, a, b)
#define FA_FMAX_E0(vd, a, b) XV_FMAX_E0(vd, a, b)
#define FA_FDIV_E0(vd, a, b) XV_FDIV_E0(vd, a, b)
#define FA_FMUL_SCALAR(vd, a, b) XV_FMUL_SCALAR(vd, a, b)
#define FA_FSUB_SCALAR(vd, a, b) XV_FSUB_SCALAR(vd, a, b)
#define FA_BEGIN_SCALARS() do {} while (0)
#define FA_END_SCALARS() do {} while (0)
#define FA_QUANTIZE(target, scale, src) XV_FPQUANT_SCALAR_TO_CUTE(target, scale, src)
#else
#define FA_KERNEL_NAME "compact_batched_v2"
#define FA_LOAD_E0(vd, addr) XV_LOAD(vd, addr, 2)
#define FA_STORE_E0(vd, addr) XV_STORE(vd, addr, 2)
#define FA_FADD_E0(vd, a, b) XV_FADD(vd, a, b, 2)
#define FA_FMUL_E0(vd, a, b) XV_FMUL(vd, a, b, 2)
#define FA_FMAX_E0(vd, a, b) XV_FMAX(vd, a, b, 2)
#define FA_FDIV_E0(vd, a, b) XV_FDIV(vd, a, b, 2)
#define FA_FMUL_SCALAR(vd, a, b) XV_FMUL(vd, a, b, 2)
#define FA_FSUB_SCALAR(vd, a, b) XV_FSUB(vd, a, b, 2)
#define FA_BEGIN_SCALARS() config_e32(1)
#define FA_END_SCALARS() config_e32(CHUNK)
#define FA_QUANTIZE(target, scale, src) do { \
  XV_BROADCAST(scale, scale, 2); XV_FPQUANT_TO_CUTE(target, scale, src); \
} while (0)
#endif
#if EVU_FLASH_MERGE_PV
#define FA_PROFILE_SCHEMA "compact_pv_pair_v2"
#define FA_PV_BUFFERS 2
#define FA_OUTPUT_PASSES 2
#else
#define FA_PROFILE_SCHEMA "compact_chunk_pv_v2"
#define FA_PV_BUFFERS 1
#define FA_OUTPUT_PASSES 4
#endif
enum {
  FA_SCALE_VL_SWITCHES = EVU_FLASH_SCALAR_OPS ? 0 : 2 * Q * (KV / CHUNK) / EVU_FLASH_ROW_BATCH,
  FA_NORMALIZE_VL_SWITCHES = EVU_FLASH_SCALAR_OPS ? 0 : 2 * Q / EVU_FLASH_ROW_BATCH
};
#define ALIGN __attribute__((aligned(64)))
#define REPEATS 3
#define BASELINE_CYCLES UINT64_C(322302) /* Historical, not a fresh matched run. */
static const float q_scale = 0.125f, k_scale = 0.2f, v_scale = 0.05f;
static const float softmax_scale = 0.125f, log2e = 1.4426950408889634f;
static int8_t q_values[Q][D] ALIGN, k_values[KV][D] ALIGN, v_values[KV][D] ALIGN;
static int8_t v_tile[TILE][CHUNK] ALIGN;
static float scores[Q][TILE] ALIGN, pv[FA_PV_BUFFERS][Q][TILE] ALIGN, output[Q][D] ALIGN;
/* Keep the legacy max allocation for the scalar0 comparison. The scalar path
 * consumes only element zero; sums and P scales have one FP32 value per row.
 */
static float row_max[Q][CHUNK] ALIGN, row_sum[Q] ALIGN;
static float p_scale[2][Q] ALIGN;
#if EVU_FLASH_FUSE_ALPHA
static float row_alpha[Q] ALIGN;
#endif
#if EVU_FLASH_EARLY_RECIP
static float row_reciprocal[Q] ALIGN;
#endif
static float constants[6][TILE] ALIGN, qk_scale[TILE] ALIGN, value_scale[TILE] ALIGN;
#ifndef EVU_FLASH_SKIP_REFERENCE
static double golden[Q][D] ALIGN, quantized_golden[Q][D] ALIGN;
static double error_bound[Q][D] ALIGN;
static unsigned reference_unique_queries, reference_unique_columns;
#endif

static inline uint64_t read_cycle(void) {
#ifdef EVU_FLASH_NATIVE
  return 0;
#else
  uint64_t x; __asm__ volatile ("rdcycle %0" : "=r"(x) :: "memory"); return x;
#endif
}
static inline uint64_t read_instret(void) {
#ifdef EVU_FLASH_NATIVE
  return 0;
#else
  uint64_t x; __asm__ volatile ("rdinstret %0" : "=r"(x) :: "memory"); return x;
#endif
}
static inline void config_e32(unsigned vl) {
  XV_CSR_WRITE(XV_EVCFG, XV_CONFIGURED | (UINT64_C(16) << 32) | vl);
}
static void sync_evu(void) {
  XV_FENCE();
  FA_MEMORY_FENCE();
}
#ifndef EVU_FLASH_SKIP_REFERENCE
static uint32_t bits(float x) { union { float f; uint32_t u; } b = { .f = x }; return b.u; }
#endif

/* The independent breakdown executable supplies these hooks. They disappear
 * completely from this test's normal build and its native semantic adapter. */
#ifndef EVU_FLASH_PHASE_BEGIN
#define EVU_FLASH_PHASE_BEGIN(phase, tile, chunk) do {} while (0)
#endif
#ifndef EVU_FLASH_PHASE_END
#define EVU_FLASH_PHASE_END(phase) do {} while (0)
#endif

static void init_inputs(void) {
  for (unsigned i = 0; i < Q * D; ++i) ((int8_t *)q_values)[i] = (int8_t)((i * 3 + 1) % 7 - 3);
  for (unsigned i = 0; i < KV * D; ++i) {
    ((int8_t *)k_values)[i] = (int8_t)((i * 5 + 2) % 9 - 4);
    ((int8_t *)v_values)[i] = (int8_t)((i * 11 + 3) % 7 - 3);
  }
  for (unsigned i = 0; i < TILE; ++i) {
    union { uint32_t u; float f; } seed = { .u = 0xff7fffff };
    constants[0][i] = seed.f;  // reduction seed: -FLT_MAX
    constants[1][i] = 0.0f;
    constants[2][i] = 127.0f;
    constants[3][i] = 1.0f / 127.0f;
    constants[4][i] = 1.0f;
    constants[5][i] = 0x1p-120f; // protects division for an underflowed chunk
    qk_scale[i] = q_scale * k_scale * softmax_scale * log2e;
    value_scale[i] = v_scale;
  }
}

static void matrix_shape(void) {
  volatile int unused;
  MSYNCRESET();
  AME_SET_I8();
  MSETCFG(x2, AME_CONF_INT8); // second P tile uses tr2
  MSETTILEM(unused, TILE);
  MSETTILEN(unused, TILE);
  MSETTILEK(unused, CHUNK);
  (void)unused;
}

static void qk_matrix(unsigned key_base) {
  EVU_FLASH_PHASE_BEGIN(QK_SUBMIT, key_base / TILE, -1);
  matrix_shape();
  config_e32(TILE);
  XV_LOAD(20, qk_scale, 2);      // M = key
  XV_LOAD(21, constants[4], 2); // N = query
  XV_LAUNCH_FP32(scores, 20, 21);
  MZERO_ACC(acc0);
  FA_MEMORY_FENCE();
  MLAE8(tr0, q_values, D);
  MLBE8(tr1, k_values[key_base], D);
  MQMA(acc0, tr0, tr1);
  MRELEASE();
  EVU_FLASH_PHASE_END(QK_SUBMIT);
  EVU_FLASH_PHASE_BEGIN(QK_WAIT_DRAIN, key_base / TILE, -1);
  MACQUIRE(1L);
  XV_WAIT();
  sync_evu();
  config_e32(CHUNK);
  EVU_FLASH_PHASE_END(QK_WAIT_DRAIN);
}

static void pv_matrix(unsigned key_base, unsigned chunk) {
  EVU_FLASH_PHASE_BEGIN(PV_PACK, key_base / TILE, chunk);
  // Keep V layout adaptation and zero padding inside end-to-end timing.
  for (unsigned d = 0; d < TILE; ++d)
    for (unsigned k = 0; k < CHUNK; ++k)
      v_tile[d][k] = d < D ? v_values[key_base + chunk * CHUNK + k][d] : 0;
  EVU_FLASH_PHASE_END(PV_PACK);
  EVU_FLASH_PHASE_BEGIN(PV_SUBMIT, key_base / TILE, chunk);
  matrix_shape();
  config_e32(TILE);
  XV_LOAD(20, value_scale, 2);   // M = output feature
  XV_LOAD(21, p_scale[chunk], 2); // N = query
  XV_LAUNCH_FP32(pv[EVU_FLASH_MERGE_PV ? chunk : 0], 20, 21);
  MZERO_ACC(acc0);
  FA_MEMORY_FENCE();
  MLBE8(tr0, v_tile, CHUNK);
  // tr1/tr2 already contain the complete directly quantized P tile.
  if (chunk == 0) MQMA(acc0, tr1, tr0);
  else MQMA(acc0, tr2, tr0);
  MRELEASE();
  EVU_FLASH_PHASE_END(PV_SUBMIT);
  EVU_FLASH_PHASE_BEGIN(PV_WAIT_DRAIN, key_base / TILE, chunk);
  MACQUIRE(1L);
  XV_WAIT();
  sync_evu();
  config_e32(CHUNK);
  EVU_FLASH_PHASE_END(PV_WAIT_DRAIN);
}

#define FA_SCHEDULE_SOFTMAX softmax_quantize_rows
#define FA_SCHEDULE_OUTPUT output_update
#define FA_SCHEDULE_KERNEL flash_attention_evu
#include "flash_attention_evu_schedule.h"
#undef FA_SCHEDULE_SOFTMAX
#undef FA_SCHEDULE_OUTPUT
#undef FA_SCHEDULE_KERNEL

#ifndef EVU_FLASH_SKIP_REFERENCE
/* Independent FP64 exp for nonpositive inputs; degree-16 polynomial after
 * base-2 range reduction. It never uses EVU intermediates or NEMU results. */
static double reference_exp(double x) {
  const double ln2 = 0.69314718055994530942;
  if (x < -700.0) return 0.0;
  int n = (int)(x / ln2);
  double r = x - n * ln2, term = 1.0, sum = 1.0;
  for (unsigned i = 1; i <= 16; ++i) { term *= r / i; sum += term; }
  union { uint64_t u; double d; } power = { .u = (uint64_t)(n + 1023) << 52 };
  return sum * power.d;
}

static void make_reference(void) {
  // Two independent oracles: unquantized FP64 attention, and the same
  // tile/chunk probability quantization algorithm evaluated with FP64 exp.
  static double score[KV], probability[KV], o[D], bound[D];
  unsigned query_source[Q], feature_source[D], unique_features[D];
  reference_unique_queries = reference_unique_columns = 0;
  // Equal V columns have equal outputs and error bounds for every query.
  // Verify all keys, so this reuse also works when the inputs are changed.
  for (unsigned d = 0; d < D; ++d) {
    feature_source[d] = d;
    for (unsigned feature = 0; feature < reference_unique_columns; ++feature) {
      unsigned previous = unique_features[feature], key = 0;
      while (key < KV && v_values[key][d] == v_values[key][previous]) ++key;
      if (key == KV) { feature_source[d] = previous; break; }
    }
    if (feature_source[d] == d) unique_features[reference_unique_columns++] = d;
  }
  for (unsigned row = 0; row < Q; ++row) {
    query_source[row] = row;
    for (unsigned previous = 0; previous < row; ++previous) {
      if (query_source[previous] == previous &&
          memcmp(q_values[row], q_values[previous], sizeof(q_values[row])) == 0) {
        query_source[row] = previous;
        break;
      }
    }
    if (query_source[row] != row) {
      unsigned previous = query_source[row];
      memcpy(golden[row], golden[previous], sizeof(golden[row]));
      memcpy(quantized_golden[row], quantized_golden[previous], sizeof(quantized_golden[row]));
      memcpy(error_bound[row], error_bound[previous], sizeof(error_bound[row]));
      continue;
    }
    ++reference_unique_queries;
    double maximum = -1e300;
    for (unsigned key = 0; key < KV; ++key) {
      int32_t dot = 0;
      for (unsigned d = 0; d < D; ++d) dot += (int32_t)q_values[row][d] * k_values[key][d];
      score[key] = (double)dot * q_scale * k_scale * softmax_scale;
      if (score[key] > maximum) maximum = score[key];
    }
    double sum = 0.0;
    for (unsigned key = 0; key < KV; ++key) { probability[key] = reference_exp(score[key] - maximum); sum += probability[key]; }
    for (unsigned feature = 0; feature < reference_unique_columns; ++feature) {
      unsigned d = unique_features[feature];
      double value = 0.0;
      for (unsigned key = 0; key < KV; ++key) value += probability[key] * v_values[key][d] * v_scale;
      golden[row][d] = value / sum;
      o[d] = bound[d] = 0.0;
    }
    double running_max = -1e300, running_sum = 0.0;
    for (unsigned tile = 0; tile < KV; tile += TILE) {
      double new_max = running_max;
      for (unsigned k = tile; k < tile + TILE; ++k) if (score[k] > new_max) new_max = score[k];
      double a = tile == 0 ? 0.0 : reference_exp(running_max - new_max);
      running_sum *= a;
      for (unsigned feature = 0; feature < reference_unique_columns; ++feature) {
        unsigned d = unique_features[feature];
        o[d] *= a; bound[d] *= a;
      }
      for (unsigned chunk = 0; chunk < 2; ++chunk) {
        double max_p = 0.0;
        unsigned begin = tile + chunk * CHUNK;
        for (unsigned k = begin; k < begin + CHUNK; ++k) {
          probability[k] = reference_exp(score[k] - new_max);
          running_sum += probability[k];
          if (probability[k] > max_p) max_p = probability[k];
        }
        const double scale = max_p > 0.0 ? max_p / 127.0 : 1.0;
        for (unsigned k = begin; k < begin + CHUNK; ++k) {
          unsigned p = (unsigned)(probability[k] / scale + 0.5);
          if (p > 127) p = 127;
          for (unsigned feature = 0; feature < reference_unique_columns; ++feature) {
            unsigned d = unique_features[feature];
            double v = v_values[k][d] * (double)v_scale;
            o[d] += p * scale * v;
            // Worst-case half-step P quantization error for this output.
            bound[d] += 0.5 * scale * (v < 0.0 ? -v : v);
          }
        }
      }
      running_max = new_max;
    }
    for (unsigned feature = 0; feature < reference_unique_columns; ++feature) {
      unsigned d = unique_features[feature];
      quantized_golden[row][d] = o[d] / running_sum;
      error_bound[row][d] = bound[d] / running_sum;
    }
    for (unsigned d = 0; d < D; ++d) {
      unsigned previous = feature_source[d];
      if (previous == d) continue;
      golden[row][d] = golden[row][previous];
      quantized_golden[row][d] = quantized_golden[row][previous];
      error_bound[row][d] = error_bound[row][previous];
    }
  }
}

static unsigned check_output(void) {
  double squared = 0.0, quant_squared = 0.0, energy = 0.0, max_error = 0.0, max_quant_error = 0.0;
  unsigned failures = 0;
  for (unsigned row = 0; row < Q; ++row) for (unsigned d = 0; d < D; ++d) {
    double actual = output[row][d];
    double e = actual - golden[row][d], q = actual - quantized_golden[row][d];
    double ae = e < 0.0 ? -e : e, aq = q < 0.0 ? -q : q;
    squared += e * e; quant_squared += q * q; energy += golden[row][d] * golden[row][d];
    if (ae > max_error) max_error = ae;
    if (aq > max_quant_error) max_quant_error = aq;
    // The first bound derives from the algorithm's P quantization step.
    // The second checks the same quantized algorithm more strictly.
    // FP32/Exp2/reduction error gets a separate small absolute allowance.
    double margin = 2e-6;
    if ((bits(output[row][d]) & 0x7f800000u) == 0x7f800000u ||
        !(ae <= error_bound[row][d] + margin) ||
        !(aq <= 2e-6 + 1e-4 * (quantized_golden[row][d] < 0.0 ?
          -quantized_golden[row][d] : quantized_golden[row][d]))) {
      if (failures < 4) printf("[FA] mismatch row=%u d=%u actual=0x%08x fp64=0x%08x quant=0x%08x\n",
        row, d, bits(output[row][d]), bits((float)golden[row][d]), bits((float)quantized_golden[row][d]));
      ++failures;
    }
  }
  double nmse = energy > 0.0 ? squared / energy : squared;
  double qnmse = energy > 0.0 ? quant_squared / energy : quant_squared;
  if (!(qnmse <= 1e-8) || !(nmse <= 2e-5)) ++failures;
  printf("[FA] acc a9=%lu n12=%lu qa9=%lu qn12=%lu err=%u\n",
    (unsigned long)(max_error * 1e9), (unsigned long)(nmse * 1e12),
    (unsigned long)(max_quant_error * 1e9), (unsigned long)(qnmse * 1e12), failures);
  return failures;
}
#endif /* !EVU_FLASH_SKIP_REFERENCE */

#if !defined(EVU_FLASH_NATIVE) && !defined(EVU_FLASH_NO_MAIN)
__attribute__((used, noinline)) static int flash_attention_test_main(void) {
  const uint64_t required_caps = XV_CAP_FPQUANT_CUTE | XV_CAP_GATHER_IMM;
  if (XV_CSR_READ(XV_EVLENB) != 512 || (XV_CSR_READ(XV_EVCAP) & required_caps) != required_caps) {
    printf("[FA] FAIL unsupported EVU\n");
    return 1;
  }
  printf("[FA] " FA_KERNEL_NAME " q=%u/%u/%u rows=%u pv=%u sc=%u arm=%u%u%u w=1 ref=1 runs=%u\n",
    Q, KV, D, EVU_FLASH_ROW_BATCH, EVU_FLASH_MERGE_PV, EVU_FLASH_SCALAR_OPS,
    EVU_FLASH_FUSE_ALPHA, EVU_FLASH_EARLY_RECIP, EVU_FLASH_CHUNK_MAX, REPEATS);
  init_inputs();
  printf("[FA] ref\n");
  uint64_t reference_start = read_cycle();
  make_reference();
  uint64_t reference_cycles = read_cycle() - reference_start;
  printf("[FA] ref_c=%lu run\n", (unsigned long)reference_cycles);
  flash_attention_evu(); // fixed warm-up; validation/printing outside timing
  if (check_output()) return 1;
  uint64_t cycles[REPEATS], instructions[REPEATS];
  for (unsigned i = 0; i < REPEATS; ++i) {
    XV_CSR_WRITE(XV_EFFLAGS, 0);
    sync_evu();
    uint64_t c = read_cycle(), n = read_instret();
    flash_attention_evu();
    instructions[i] = read_instret() - n;
    cycles[i] = read_cycle() - c;
    if (check_output() || (XV_CSR_READ(XV_EVSTATUS) & 2) || (XV_CSR_READ(XV_EFFLAGS) & 0x1c)) return 1;
    printf("[FA] run=%u c=%lu i=%lu fl=%lx\n",
      i, (unsigned long)cycles[i], (unsigned long)instructions[i], (unsigned long)XV_CSR_READ(XV_EFFLAGS));
  }
  for (unsigned i = 0; i < REPEATS; ++i) for (unsigned j = i + 1; j < REPEATS; ++j)
    if (cycles[j] < cycles[i]) { uint64_t t = cycles[i]; cycles[i] = cycles[j]; cycles[j] = t; }
  uint64_t median = cycles[REPEATS / 2];
  printf("[FA] PASS median=%lu rvv_hist=%lu speedup1000=%lu\n",
    (unsigned long)median, (unsigned long)BASELINE_CYCLES, (unsigned long)(BASELINE_CYCLES * 1000 / median));
  return 0;
}

__attribute__((naked)) int main(void) {
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail flash_attention_test_main\n");
}

#endif /* !EVU_FLASH_NATIVE && !EVU_FLASH_NO_MAIN */
