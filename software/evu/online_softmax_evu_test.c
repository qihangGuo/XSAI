#include <klib.h>
#include <stdint.h>
#include "evu_csr.h"

/* Pure FP32 online update, matching the inputs and output contract of
 * softmax_online_rvv_vfexp2_4row_lmul4_positive_scale_vector_alpha.
 * Scores become unnormalized exponentials; max/sum/alpha are compact FP32.
 * No CUTE, quantization, or final probability normalization is performed.
 */
#ifndef EVU_SOFTMAX_ROWS
#define EVU_SOFTMAX_ROWS 32
#endif
#ifndef EVU_SOFTMAX_COLS
#define EVU_SOFTMAX_COLS 256
#endif
#ifndef EVU_SOFTMAX_STRIDE
#define EVU_SOFTMAX_STRIDE EVU_SOFTMAX_COLS
#endif
#ifndef EVU_SOFTMAX_SCALE
#define EVU_SOFTMAX_SCALE 1.0f
#endif
#ifndef EVU_SOFTMAX_FIRST
#define EVU_SOFTMAX_FIRST -1 /* -1 measures first=0 and first=1 separately. */
#endif
#ifndef EVU_SOFTMAX_WARMUP
#define EVU_SOFTMAX_WARMUP 1
#endif
#ifndef EVU_SOFTMAX_CHECK
#define EVU_SOFTMAX_CHECK 0
#endif
#ifndef EVU_SOFTMAX_STATE_BATCH
#define EVU_SOFTMAX_STATE_BATCH 0
#endif
#ifndef EVU_SOFTMAX_REDUCE_VL
#define EVU_SOFTMAX_REDUCE_VL 128
#endif
#ifndef EVU_SOFTMAX_PIPELINE
#if EVU_SOFTMAX_COLS == 256 && !EVU_SOFTMAX_STATE_BATCH && EVU_SOFTMAX_REDUCE_VL == 128
#define EVU_SOFTMAX_PIPELINE 1
#else
#define EVU_SOFTMAX_PIPELINE 0
#endif
#endif
#ifndef EVU_SOFTMAX_STATE_LOAD_BATCH
/* Candidate: fewer memory commands, but extra draining EVCFG writes. */
#define EVU_SOFTMAX_STATE_LOAD_BATCH 0
#endif
#ifndef EVU_SOFTMAX_DRAIN_DIAG
#define EVU_SOFTMAX_DRAIN_DIAG 1
#endif
#if EVU_SOFTMAX_ROWS <= 0 || EVU_SOFTMAX_ROWS % 4 != 0
#error "EVU_SOFTMAX_ROWS must be a positive multiple of four"
#endif
#if EVU_SOFTMAX_COLS <= 0 || EVU_SOFTMAX_STRIDE < EVU_SOFTMAX_COLS
#error "cols must be positive and stride must be at least cols"
#endif
#if EVU_SOFTMAX_FIRST < -1 || EVU_SOFTMAX_FIRST > 1
#error "EVU_SOFTMAX_FIRST must be -1, 0 or 1"
#endif
#if EVU_SOFTMAX_WARMUP != 0 && EVU_SOFTMAX_WARMUP != 1
#error "EVU_SOFTMAX_WARMUP must be 0 or 1"
#endif
#if EVU_SOFTMAX_CHECK != 0 && EVU_SOFTMAX_CHECK != 1
#error "EVU_SOFTMAX_CHECK must be 0 or 1"
#endif
#if EVU_SOFTMAX_STATE_BATCH != 0 && EVU_SOFTMAX_STATE_BATCH != 1
#error "EVU_SOFTMAX_STATE_BATCH must be 0 or 1"
#endif
#if EVU_SOFTMAX_REDUCE_VL != 32 && EVU_SOFTMAX_REDUCE_VL != 64 && EVU_SOFTMAX_REDUCE_VL != 128
#error "EVU_SOFTMAX_REDUCE_VL must be 32, 64 or 128"
#endif
#if EVU_SOFTMAX_REDUCE_VL != 128 && EVU_SOFTMAX_COLS != 256
#error "short-reduction experiments currently require cols=256"
#endif
#if EVU_SOFTMAX_DRAIN_DIAG != 0 && EVU_SOFTMAX_DRAIN_DIAG != 1
#error "EVU_SOFTMAX_DRAIN_DIAG must be 0 or 1"
#endif
#if EVU_SOFTMAX_PIPELINE != 0 && EVU_SOFTMAX_PIPELINE != 1
#error "EVU_SOFTMAX_PIPELINE must be 0 or 1"
#endif
#if EVU_SOFTMAX_PIPELINE && (EVU_SOFTMAX_COLS != 256 || EVU_SOFTMAX_STATE_BATCH || EVU_SOFTMAX_REDUCE_VL != 128)
#error "the pipelined schedule requires cols=256, state_batch=0 and reduce_vl=128"
#endif
#if EVU_SOFTMAX_STATE_LOAD_BATCH && !EVU_SOFTMAX_PIPELINE
#error "state-load batching requires the wavefront schedule"
#endif
#if EVU_SOFTMAX_STATE_LOAD_BATCH != 0 && EVU_SOFTMAX_STATE_LOAD_BATCH != 1
#error "EVU_SOFTMAX_STATE_LOAD_BATCH must be 0 or 1"
#endif

enum {
  ROWS = EVU_SOFTMAX_ROWS, COLS = EVU_SOFTMAX_COLS,
  STRIDE = EVU_SOFTMAX_STRIDE, ELEMENTS = ROWS * COLS,
  VL_MAX = 128, GUARD_BYTES = 64
};
#if EVU_SOFTMAX_COLS <= 128 || EVU_SOFTMAX_COLS == 256
#define OS_RESIDENT 1
#if EVU_SOFTMAX_PIPELINE
#define OS_SCHEDULE "wave4x2"
#else
#define OS_SCHEDULE "resident_pair2"
#endif
#else
#define OS_RESIDENT 0
#define OS_SCHEDULE "stream"
#endif
#if EVU_SOFTMAX_STATE_BATCH && !OS_RESIDENT
#error "state batching currently requires the four-row resident path"
#endif
#define ALIGN __attribute__((aligned(64)))
static struct {
  uint8_t before[GUARD_BYTES];
  float data[ROWS * STRIDE];
  uint8_t after[GUARD_BYTES];
} scores ALIGN;
struct GuardedState {
  uint8_t before[GUARD_BYTES];
  float data[ROWS];
  uint8_t after[GUARD_BYTES];
} ALIGN;
static struct GuardedState row_max, row_sum, row_alpha;
static float input_template[ROWS * STRIDE] ALIGN;
static float seeds[2][VL_MAX] ALIGN;
static float parameters[3] ALIGN; /* scale, scale * log2(e), log2(e). */
static volatile float online_scale = EVU_SOFTMAX_SCALE;
#if EVU_SOFTMAX_STATE_BATCH
static float state_pack[4] ALIGN;
#endif
#if EVU_SOFTMAX_REDUCE_VL != 128
static uint32_t fold_indices[128 / EVU_SOFTMAX_REDUCE_VL - 1][EVU_SOFTMAX_REDUCE_VL] ALIGN;
#endif

#define ONLINE_SOFTMAX_CHECK EVU_SOFTMAX_CHECK
#include "online_softmax_bench.h"

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
}

static inline void configure(unsigned vl) {
  /* e32, m1, tail-undisturbed: short chunks preserve the accumulator tail. */
  XV_CSR_WRITE(XV_EVCFG, XV_CONFIGURED | (UINT64_C(16) << 32) | vl);
}

#if !OS_RESIDENT
static inline void select_vl(unsigned vl, unsigned *current) {
  if (vl != *current) {
    configure(vl);
    *current = vl;
  }
}
#endif
#if EVU_SOFTMAX_CHECK
static void initialize_guards(uint8_t *before, uint8_t *after) {
  for (unsigned i = 0; i < GUARD_BYTES; ++i) {
    before[i] = 0xa5;
    after[i] = 0x5a;
  }
}
#endif

static void initialize_input(void) {
#if EVU_SOFTMAX_CHECK
  initialize_guards(scores.before, scores.after);
  initialize_guards(row_max.before, row_max.after);
  initialize_guards(row_sum.before, row_sum.after);
  initialize_guards(row_alpha.before, row_alpha.after);
#endif
  for (unsigned row = 0; row < ROWS; ++row) {
    for (unsigned col = 0; col < STRIDE; ++col)
      input_template[row * STRIDE + col] = col < COLS ? input_value(row, col) : 0.0f;
  }
}

static void reset_case(void) {
  memcpy(scores.data, input_template, sizeof(input_template));
  for (unsigned row = 0; row < ROWS; ++row) {
    row_max.data[row] = initial_max(row);
    row_sum.data[row] = initial_sum(row);
    row_alpha.data[row] = 0.0f;
  }
}

/* Keep two chunks per row in v0..v7, max/alpha/old-sum in v12..v23.
 * v8..v11 hold max scratch, then exp bias, then sum. Offset their row
 * assignment so each bias differs in bank from both of that row's chunks.
 * The streaming fallback reuses its spare second-chunk register for bias.
 */
#define OS_PAIR0(OP) \
  OP(0, 0, 4, 10, 12, 16, 20) OP(1, 1, 5, 11, 13, 17, 21)
#define OS_PAIR1(OP) \
  OP(2, 2, 6, 8, 14, 18, 22) OP(3, 3, 7, 9, 15, 19, 23)
#define OS_ROWS(OP) OS_PAIR0(OP) OS_PAIR1(OP)
#define OS_INIT_MAX(lane, value, tail, acc, max, alpha, old_sum) XV_FMAX(acc, 28, 28, 2);
#define OS_LOAD(lane, value, tail, acc, max, alpha, old_sum) \
  XV_LOAD(value, scores.data + (base + (lane)) * STRIDE + offset, 2);
#define OS_LOAD_HEAD(lane, value, tail, acc, max, alpha, old_sum) \
  XV_LOAD(value, scores.data + (base + (lane)) * STRIDE, 2);
#define OS_LOAD_TAIL(lane, value, tail, acc, max, alpha, old_sum) \
  XV_LOAD(tail, scores.data + (base + (lane)) * STRIDE + VL_MAX, 2);
#define OS_MAX(lane, value, tail, acc, max, alpha, old_sum) XV_FMAX(acc, acc, value, 2);
#define OS_PAIR_MAX(lane, value, tail, acc, max, alpha, old_sum) XV_FMAX(acc, value, tail, 2);
#define OS_REDUCE_MAX(lane, value, tail, acc, max, alpha, old_sum) XV_FREDUCE_MAX(max, 28, acc, 2);
#define OS_HEAD_MAX(lane, value, tail, acc, max, alpha, old_sum) XV_FREDUCE_MAX(max, 28, value, 2);
#define OS_SCALE_MAX(lane, value, tail, acc, max, alpha, old_sum) XV_FMUL_E0(max, 27, max);
#define OS_LOAD_OLD_MAX(lane, value, tail, acc, max, alpha, old_sum) \
  XV_LOAD_E0(alpha, &row_max.data[base + (lane)]);
#define OS_MERGE_MAX(lane, value, tail, acc, max, alpha, old_sum) XV_FMAX_E0(max, alpha, max);
#define OS_ALPHA_DIFF(lane, value, tail, acc, max, alpha, old_sum) XV_FSUB_E0(alpha, max, alpha);
#define OS_ALPHA_LOG2(lane, value, tail, acc, max, alpha, old_sum) XV_FMUL_E0(alpha, 31, alpha);
#define OS_ALPHA_EXP(lane, value, tail, acc, max, alpha, old_sum) XV_EXP2_E0(alpha, alpha);
#define OS_SAVE_ALPHA(lane, value, tail, acc, max, alpha, old_sum) \
  XV_STORE_E0(alpha, &row_alpha.data[base + (lane)]);
#define OS_ZERO_ALPHA(lane, value, tail, acc, max, alpha, old_sum) \
  XV_STORE_E0(29, &row_alpha.data[base + (lane)]);
#define OS_SAVE_MAX(lane, value, tail, acc, max, alpha, old_sum) \
  XV_STORE_E0(max, &row_max.data[base + (lane)]);
#define OS_RESIDENT_BIAS(lane, value, tail, acc, max, alpha, old_sum) XV_FMUL_E0(acc, 31, max);
#define OS_STREAM_BIAS(lane, value, tail, acc, max, alpha, old_sum) XV_FMUL_E0(tail, 31, max);
#define OS_INIT_SUM(lane, value, tail, acc, max, alpha, old_sum) XV_FMAX(acc, 29, 29, 2);
#define OS_SCALE(lane, value, tail, acc, max, alpha, old_sum) XV_FMUL_SCALAR(value, 30, value);
#define OS_RESIDENT_SHIFT(lane, value, tail, acc, max, alpha, old_sum) XV_FSUB_SCALAR(value, acc, value);
#define OS_STREAM_SHIFT(lane, value, tail, acc, max, alpha, old_sum) XV_FSUB_SCALAR(value, tail, value);
#define OS_EXP(lane, value, tail, acc, max, alpha, old_sum) XV_EXP2(value, value, 2);
#define OS_TAIL_SCALE(lane, value, tail, acc, max, alpha, old_sum) XV_FMUL_SCALAR(tail, 30, tail);
#define OS_TAIL_SHIFT(lane, value, tail, acc, max, alpha, old_sum) XV_FSUB_SCALAR(tail, acc, tail);
#define OS_TAIL_EXP(lane, value, tail, acc, max, alpha, old_sum) XV_EXP2(tail, tail, 2);
#define OS_SUM(lane, value, tail, acc, max, alpha, old_sum) XV_FADD(acc, acc, value, 2);
#define OS_PAIR_SUM(lane, value, tail, acc, max, alpha, old_sum) XV_FADD(acc, value, tail, 2);
#define OS_STORE(lane, value, tail, acc, max, alpha, old_sum) \
  XV_STORE(value, scores.data + (base + (lane)) * STRIDE + offset, 2);
#define OS_STORE_HEAD(lane, value, tail, acc, max, alpha, old_sum) \
  XV_STORE(value, scores.data + (base + (lane)) * STRIDE, 2);
#define OS_STORE_TAIL(lane, value, tail, acc, max, alpha, old_sum) \
  XV_STORE(tail, scores.data + (base + (lane)) * STRIDE + VL_MAX, 2);
#define OS_REDUCE_SUM(lane, value, tail, acc, max, alpha, old_sum) XV_FREDUCE_SUM(acc, 29, acc, 2);
#define OS_HEAD_SUM(lane, value, tail, acc, max, alpha, old_sum) XV_FREDUCE_SUM(acc, 29, value, 2);
#define OS_LOAD_OLD_SUM(lane, value, tail, acc, max, alpha, old_sum) \
  XV_LOAD_E0(old_sum, &row_sum.data[base + (lane)]);
#define OS_RESCALE_SUM(lane, value, tail, acc, max, alpha, old_sum) XV_FMUL_E0(old_sum, alpha, old_sum);
#define OS_ADD_SUM(lane, value, tail, acc, max, alpha, old_sum) XV_FADD_E0(acc, old_sum, acc);
#define OS_SAVE_SUM(lane, value, tail, acc, max, alpha, old_sum) \
  XV_STORE_E0(acc, &row_sum.data[base + (lane)]);
/* No new insert/slide ISA is assumed. Packing uses measured EVU scratch
 * stores plus a four-element load; this cost may outweigh state-load savings. */
#define OS_PACK4(vd, a, b, c, d) do { \
  XV_STORE_E0(a, &state_pack[0]); \
  XV_STORE_E0(b, &state_pack[1]); \
  XV_STORE_E0(c, &state_pack[2]); \
  XV_STORE_E0(d, &state_pack[3]); \
  XV_LOAD(vd, state_pack, 2); \
} while (0)
#define OS_FOLD_MAX(lane, value, tail, acc, max, alpha, old_sum) \
  XV_GATHER(24, 25, acc, 2); XV_FMAX(acc, acc, 24, 2);
#define OS_FOLD_SUM(lane, value, tail, acc, max, alpha, old_sum) \
  XV_GATHER(24, 25, acc, 2); XV_FADD(acc, acc, 24, 2);
#if OS_RESIDENT
#if EVU_SOFTMAX_COLS > 128
#define OS_RESIDENT_MAX OS_REDUCE_MAX
#define OS_RESIDENT_SUM OS_REDUCE_SUM
#else
#define OS_RESIDENT_MAX OS_HEAD_MAX
#define OS_RESIDENT_SUM OS_HEAD_SUM
#endif
#endif

/* Alternate v0..v7 and v8..v15: four active rows plus four prefetched rows.
 * v16..v19 are raw max -> max -> exp bias -> pair sum -> reduced sum;
 * v20..v23 are old max -> alpha -> separately rounded alpha * old sum.
 * Offset state banks from both chunks. v24/v25 are two old-sum temporaries;
 * v27..v31 keep the five constants. No scratch stores. The optional four-state
 * loads add two draining VL changes per four rows for first=0 only.
 */
#if EVU_SOFTMAX_STATE_LOAD_BATCH
/* Borrow the not-yet-prefetched bank for old sums. Each pair consumes these
 * scalars before its corresponding prefetch can overwrite the registers. */
#define OS_A0(OP) OP(0, 0, 4, 18, 18, 22, 8) OP(1, 1, 5, 19, 19, 23, 9)
#define OS_A1(OP) OP(2, 2, 6, 16, 16, 20, 10) OP(3, 3, 7, 17, 17, 21, 11)
#define OS_B0(OP) OP(0, 8, 12, 18, 18, 22, 0) OP(1, 9, 13, 19, 19, 23, 1)
#define OS_B1(OP) OP(2, 10, 14, 16, 16, 20, 2) OP(3, 11, 15, 17, 17, 21, 3)
#define OS_PIPE_GET_STATE(lane, value, tail, acc, max, alpha, old_sum) \
  XV_GATHER_IMM(alpha, lane, 24, 2); \
  XV_GATHER_IMM(old_sum, lane, 25, 2);
#define OS_PIPE_STATE(PAIR0, PAIR1) do { \
  if (!first) { \
    configure(4); \
    XV_LOAD(24, &row_max.data[base], 2); \
    XV_LOAD(25, &row_sum.data[base], 2); \
    PAIR0(OS_PIPE_GET_STATE) PAIR1(OS_PIPE_GET_STATE) \
    configure(VL_MAX); \
  } \
} while (0)
#define OS_PIPE_LOAD_MAX(PAIR) do { } while (0)
#define OS_PIPE_LOAD_SUM(PAIR) do { } while (0)
#else
#define OS_A0(OP) OP(0, 0, 4, 18, 18, 22, 24) OP(1, 1, 5, 19, 19, 23, 25)
#define OS_A1(OP) OP(2, 2, 6, 16, 16, 20, 24) OP(3, 3, 7, 17, 17, 21, 25)
#define OS_B0(OP) OP(0, 8, 12, 18, 18, 22, 24) OP(1, 9, 13, 19, 19, 23, 25)
#define OS_B1(OP) OP(2, 10, 14, 16, 16, 20, 24) OP(3, 11, 15, 17, 17, 21, 25)
#define OS_PIPE_STATE(PAIR0, PAIR1) do { } while (0)
#define OS_PIPE_LOAD_MAX(PAIR) do { PAIR(OS_LOAD_OLD_MAX) } while (0)
#define OS_PIPE_LOAD_SUM(PAIR) do { PAIR(OS_LOAD_OLD_SUM) } while (0)
#endif
#define OS_PIPE_LOAD(lane, value, tail, acc, max, alpha, old_sum) \
  OS_LOAD_HEAD(lane, value, tail, acc, max, alpha, old_sum) \
  OS_LOAD_TAIL(lane, value, tail, acc, max, alpha, old_sum)
#define OS_PIPE_PREFETCH(lane, value, tail, acc, max, alpha, old_sum) \
  XV_LOAD(value, scores.data + (next_base + (lane)) * STRIDE, 2); \
  XV_LOAD(tail, scores.data + (next_base + (lane)) * STRIDE + VL_MAX, 2);
#define OS_PIPE_RESCALE(lane, value, tail, acc, max, alpha, old_sum) \
  XV_FMUL_E0(alpha, alpha, old_sum);
#define OS_PIPE_ADD_SUM(lane, value, tail, acc, max, alpha, old_sum) \
  XV_FADD_E0(acc, alpha, acc);
/* Keep state publication before overwriting max/alpha. Prepare the first
 * pair before submitting another max reduction; interleave head EXP2 with
 * tail shifts, and next-batch loads with independent current-batch work.
 */
#define OS_PIPE_PREPARE(PAIR) do { \
  if (scale != 1.0f) { PAIR(OS_SCALE_MAX) } \
  if (!first) { \
    PAIR(OS_MERGE_MAX) PAIR(OS_ALPHA_DIFF) \
    PAIR(OS_ALPHA_LOG2) PAIR(OS_ALPHA_EXP) \
    PAIR(OS_SAVE_ALPHA) \
  } else { PAIR(OS_ZERO_ALPHA) } \
  PAIR(OS_SAVE_MAX) PAIR(OS_RESIDENT_BIAS) \
} while (0)
#define OS_PIPE_STEP(PAIR0, PAIR1, NEXT0, NEXT1, current, next) do { \
  const unsigned base = (current); \
  const unsigned next_base = (next); \
  OS_PIPE_STATE(PAIR0, PAIR1); \
  PAIR0(OS_PAIR_MAX) PAIR0(OS_REDUCE_MAX) \
  PAIR1(OS_PAIR_MAX) \
  if (!first) { OS_PIPE_LOAD_MAX(PAIR0); } \
  PAIR0(OS_SCALE) PAIR0(OS_TAIL_SCALE) \
  OS_PIPE_PREPARE(PAIR0); \
  PAIR0(OS_RESIDENT_SHIFT) PAIR0(OS_EXP) \
  PAIR0(OS_TAIL_SHIFT) PAIR0(OS_TAIL_EXP) \
  PAIR1(OS_REDUCE_MAX) \
  PAIR1(OS_SCALE) PAIR1(OS_TAIL_SCALE) \
  if (!first) { \
    OS_PIPE_LOAD_SUM(PAIR0); PAIR0(OS_PIPE_RESCALE) \
    OS_PIPE_LOAD_MAX(PAIR1); \
  } \
  if (next_base < ROWS) { NEXT0(OS_PIPE_PREFETCH) } \
  OS_PIPE_PREPARE(PAIR1); \
  PAIR1(OS_RESIDENT_SHIFT) PAIR1(OS_EXP) \
  PAIR1(OS_TAIL_SHIFT) PAIR1(OS_TAIL_EXP) \
  PAIR0(OS_PAIR_SUM) PAIR0(OS_REDUCE_SUM) \
  PAIR0(OS_STORE_HEAD) PAIR0(OS_STORE_TAIL) \
  if (!first) { OS_PIPE_LOAD_SUM(PAIR1); PAIR1(OS_PIPE_RESCALE) } \
  if (next_base < ROWS) { NEXT1(OS_PIPE_PREFETCH) } \
  PAIR1(OS_PAIR_SUM) PAIR1(OS_REDUCE_SUM) \
  if (!first) { PAIR0(OS_PIPE_ADD_SUM) } \
  PAIR0(OS_SAVE_SUM) \
  PAIR1(OS_STORE_HEAD) PAIR1(OS_STORE_TAIL) \
  if (!first) { PAIR1(OS_PIPE_ADD_SUM) } \
  PAIR1(OS_SAVE_SUM) \
} while (0)

__attribute__((noinline)) static void online_update(unsigned first) {
  const float scale = online_scale;
  parameters[0] = scale;
  parameters[1] = scale * 0x1.715476p0f;
  parameters[2] = 0x1.715476p0f;
  __asm__ volatile ("fence rw, rw" ::: "memory");
  configure(COLS < VL_MAX ? COLS : VL_MAX);
#if OS_RESIDENT
  XV_LOAD_E0(28, &seeds[0][0]);
  XV_LOAD_E0(29, &seeds[1][0]);
#else
  unsigned current_vl = VL_MAX;
  XV_LOAD(28, seeds[0], 2);
  XV_LOAD(29, seeds[1], 2);
#endif
  XV_LOAD_E0(27, &parameters[0]);
  XV_LOAD_E0(30, &parameters[1]);
  XV_LOAD_E0(31, &parameters[2]);
#if EVU_SOFTMAX_PIPELINE
  {
    const unsigned base = 0;
    OS_A0(OS_PIPE_LOAD) OS_A1(OS_PIPE_LOAD)
  }
  for (unsigned block = 0; block < ROWS; block += 8) {
    OS_PIPE_STEP(OS_A0, OS_A1, OS_B0, OS_B1, block, block + 4);
    if (block + 4 < ROWS) {
      OS_PIPE_STEP(OS_B0, OS_B1, OS_A0, OS_A1, block + 4, block + 8);
    }
  }
#else
  for (unsigned base = 0; base < ROWS; base += 4) {
#if OS_RESIDENT
    OS_ROWS(OS_LOAD_HEAD)
#if EVU_SOFTMAX_COLS > 128
    OS_ROWS(OS_LOAD_TAIL)
    OS_ROWS(OS_PAIR_MAX)
#endif
#else
    OS_ROWS(OS_INIT_MAX)
    for (unsigned offset = 0; offset < COLS; offset += VL_MAX) {
      const unsigned vl = COLS - offset < VL_MAX ? COLS - offset : VL_MAX;
      select_vl(vl, &current_vl);
      OS_ROWS(OS_LOAD)
      OS_ROWS(OS_MAX)
    }
    select_vl(VL_MAX, &current_vl);
#endif
    if (!first && !EVU_SOFTMAX_STATE_BATCH) {
      OS_ROWS(OS_LOAD_OLD_MAX)
      OS_ROWS(OS_LOAD_OLD_SUM)
    }
#if OS_RESIDENT
#if EVU_SOFTMAX_REDUCE_VL != 128
    configure(EVU_SOFTMAX_REDUCE_VL);
    for (unsigned group = 0; group < 128 / EVU_SOFTMAX_REDUCE_VL - 1; ++group) {
      XV_LOAD(25, fold_indices[group], 2);
      OS_ROWS(OS_FOLD_MAX)
    }
#endif
    /* Scale only after the raw maximum has consumed this pair's inputs.
     * These independent vector operations separate the two reduction pairs. */
    OS_PAIR0(OS_RESIDENT_MAX)
#if EVU_SOFTMAX_REDUCE_VL != 128
    configure(VL_MAX);
#endif
    OS_PAIR0(OS_SCALE)
#if EVU_SOFTMAX_COLS > 128
    OS_PAIR0(OS_TAIL_SCALE)
#endif
#if EVU_SOFTMAX_REDUCE_VL != 128
    configure(EVU_SOFTMAX_REDUCE_VL);
#endif
    OS_PAIR1(OS_RESIDENT_MAX)
#if EVU_SOFTMAX_REDUCE_VL != 128
    configure(VL_MAX);
#endif
    OS_PAIR1(OS_SCALE)
#if EVU_SOFTMAX_COLS > 128
    OS_PAIR1(OS_TAIL_SCALE)
#endif
#else
    OS_ROWS(OS_REDUCE_MAX)
#endif
#if EVU_SOFTMAX_STATE_BATCH
    configure(4);
    OS_PACK4(24, 12, 13, 14, 15);
    if (scale != 1.0f) { XV_FMUL_SCALAR(24, 27, 24); }
    if (!first) {
      XV_LOAD(25, &row_max.data[base], 2);
      XV_LOAD(26, &row_sum.data[base], 2);
      XV_FMAX(24, 25, 24, 2);
      XV_FSUB(25, 24, 25, 2);
      XV_FMUL_SCALAR(25, 31, 25);
      XV_EXP2(25, 25, 2);
      XV_FMUL(26, 25, 26, 2);
    } else {
      XV_BROADCAST(25, 29, 2);
    }
    XV_STORE(24, &row_max.data[base], 2);
    XV_STORE(25, &row_alpha.data[base], 2);
    XV_GATHER_IMM(12, 0, 24, 2);
    XV_GATHER_IMM(13, 1, 24, 2);
    XV_GATHER_IMM(14, 2, 24, 2);
    XV_GATHER_IMM(15, 3, 24, 2);
    configure(COLS < VL_MAX ? COLS : VL_MAX);
#else
    if (scale != 1.0f) { OS_ROWS(OS_SCALE_MAX) }
    if (!first) {
      OS_ROWS(OS_MERGE_MAX)
      OS_ROWS(OS_ALPHA_DIFF)
      OS_ROWS(OS_ALPHA_LOG2)
      OS_ROWS(OS_ALPHA_EXP)
      OS_ROWS(OS_RESCALE_SUM)
    }
#endif
#if OS_RESIDENT
    OS_ROWS(OS_RESIDENT_BIAS)
    OS_ROWS(OS_RESIDENT_SHIFT)
    OS_ROWS(OS_EXP)
#if EVU_SOFTMAX_COLS > 128
    OS_ROWS(OS_TAIL_SHIFT)
    OS_ROWS(OS_TAIL_EXP)
    OS_ROWS(OS_PAIR_SUM)
#endif
#if EVU_SOFTMAX_REDUCE_VL != 128
    configure(EVU_SOFTMAX_REDUCE_VL);
    for (unsigned group = 0; group < 128 / EVU_SOFTMAX_REDUCE_VL - 1; ++group) {
      XV_LOAD(25, fold_indices[group], 2);
      OS_ROWS(OS_FOLD_SUM)
    }
#endif
    /* Probability stores do not depend on the sum result. Submit them
     * before the next pair can block dispatch waiting for a reduction slot. */
    OS_PAIR0(OS_RESIDENT_SUM)
#if EVU_SOFTMAX_REDUCE_VL != 128
    configure(VL_MAX);
#endif
    OS_PAIR0(OS_STORE_HEAD)
#if EVU_SOFTMAX_COLS > 128
    OS_PAIR0(OS_STORE_TAIL)
#endif
#if EVU_SOFTMAX_REDUCE_VL != 128
    configure(EVU_SOFTMAX_REDUCE_VL);
#endif
    OS_PAIR1(OS_RESIDENT_SUM)
#if EVU_SOFTMAX_REDUCE_VL != 128
    configure(VL_MAX);
#endif
    OS_PAIR1(OS_STORE_HEAD)
#if EVU_SOFTMAX_COLS > 128
    OS_PAIR1(OS_STORE_TAIL)
#endif
#else
    OS_ROWS(OS_STREAM_BIAS)
    OS_ROWS(OS_INIT_SUM)
    for (unsigned offset = 0; offset < COLS; offset += VL_MAX) {
      const unsigned vl = COLS - offset < VL_MAX ? COLS - offset : VL_MAX;
      select_vl(vl, &current_vl);
      OS_ROWS(OS_LOAD)
      OS_ROWS(OS_SCALE)
      OS_ROWS(OS_STREAM_SHIFT)
      OS_ROWS(OS_EXP)
      OS_ROWS(OS_SUM)
      OS_ROWS(OS_STORE)
    }
    select_vl(VL_MAX, &current_vl);
    OS_ROWS(OS_REDUCE_SUM)
#endif
#if EVU_SOFTMAX_STATE_BATCH
    configure(4);
    OS_PACK4(24, 10, 11, 8, 9);
    if (!first) { XV_FADD(24, 26, 24, 2); }
    XV_STORE(24, &row_sum.data[base], 2);
    configure(COLS < VL_MAX ? COLS : VL_MAX);
#else
    if (!first) {
      OS_ROWS(OS_ADD_SUM)
    }
    OS_ROWS(OS_SAVE_MAX)
    OS_ROWS(OS_SAVE_SUM)
    if (!first) { OS_ROWS(OS_SAVE_ALPHA) }
    else { OS_ROWS(OS_ZERO_ALPHA) }
#endif
  }
#endif
  /* The harness drains completion inside both timed and diagnostic runs. */
}

#if EVU_SOFTMAX_CHECK
static unsigned check_guards(const uint8_t *before, const uint8_t *after) {
  for (unsigned i = 0; i < GUARD_BYTES; ++i)
    if (before[i] != 0xa5 || after[i] != 0x5a) return 1;
  return 0;
}

static unsigned check_output(unsigned first, double *score_nmse, double *state_nmse) {
  const unsigned failures = online_softmax_validate(first, scores.data,
    row_max.data, row_sum.data, row_alpha.data, online_scale,
    ROWS, COLS, STRIDE, score_nmse, state_nmse);
  const unsigned guard_errors = check_guards(scores.before, scores.after) +
    check_guards(row_max.before, row_max.after) + check_guards(row_sum.before, row_sum.after) +
    check_guards(row_alpha.before, row_alpha.after);
  if (guard_errors) printf("[OS] guards first=%u err=%u\n", first, guard_errors);
  return failures + guard_errors;
}
#endif

static int run_case(unsigned first) {
  uint64_t warm_cycles = 0;
  reset_case();
  synchronize();
  if (EVU_SOFTMAX_WARMUP) {
    const uint64_t start = read_cycle();
    online_update(first);
    synchronize();
    warm_cycles = read_cycle() - start;
    if ((XV_CSR_READ(XV_EVSTATUS) & 2) || (XV_CSR_READ(XV_EFFLAGS) & 0x1c)) {
      printf("[OS] FAIL warm first=%u cause=%lu\n", first, (unsigned long)XV_CSR_READ(XV_EVCAUSE));
      return 1;
    }
#if EVU_SOFTMAX_CHECK
    double warm_score_nmse, warm_state_nmse;
    if (check_output(first, &warm_score_nmse, &warm_state_nmse)) return 1;
#endif
    reset_case();
    synchronize();
  }
  XV_CSR_WRITE(XV_EFFLAGS, 0);
  const uint64_t start_instret = read_instret(), start_cycle = read_cycle();
  online_update(first);
  synchronize();
  const uint64_t end_cycle = read_cycle();
  const uint64_t cycles = end_cycle - start_cycle;
  const uint64_t instructions = read_instret() - start_instret;
  const uint64_t status = XV_CSR_READ(XV_EVSTATUS), flags = XV_CSR_READ(XV_EFFLAGS);
  unsigned failures = !cycles || (status & 2) || (flags & 0x1c);
#if EVU_SOFTMAX_CHECK
  double score_nmse, state_nmse;
  failures += check_output(first, &score_nmse, &state_nmse);
  /* On failure NMSE may be NaN; do not convert it to an integer. */
  const unsigned long score_n12 = score_nmse >= 0.0 && score_nmse <= 1.0 ?
    (unsigned long)(score_nmse * 1e12) : UINT64_C(1000000000000);
  const unsigned long state_n12 = state_nmse >= 0.0 && state_nmse <= 1.0 ?
    (unsigned long)(state_nmse * 1e12) : UINT64_C(1000000000000);
#endif
#if EVU_SOFTMAX_DRAIN_DIAG
  /* Separate invocation: the primary c result has no pre-fence cycle probe.
   * rdcycle can let work drain before the fence; this is a perturbed tail
   * diagnostic, including ordering overhead, and is never subtracted from c. */
  reset_case();
  synchronize();
  XV_CSR_WRITE(XV_EFFLAGS, 0);
  const uint64_t diag_start = read_cycle();
  online_update(first);
  const uint64_t diag_before = read_cycle();
  synchronize();
  const uint64_t diag_after = read_cycle();
  const uint64_t empty_start = read_cycle();
  synchronize();
  const uint64_t empty_drain = read_cycle() - empty_start;
  failures += !!((XV_CSR_READ(XV_EVSTATUS) & 2) || (XV_CSR_READ(XV_EFFLAGS) & 0x1c));
#if EVU_SOFTMAX_CHECK
  failures += check_output(first, &score_nmse, &state_nmse);
#endif
#endif
  const uint64_t rate = cycles ? (uint64_t)ELEMENTS * 1000 / cycles : 0;
  printf("[OS] first=%u c=%lu i=%lu warm_c=%lu epc=%lu.%03lu"
#if EVU_SOFTMAX_DRAIN_DIAG
    " pre=%lu full=%lu drain=%lu empty=%lu"
#endif
#if EVU_SOFTMAX_CHECK
    " sn12=%lu stn12=%lu"
#endif
    " t=%lu:%lu fl=%lx err=%u\n",
    first, (unsigned long)cycles, (unsigned long)instructions, (unsigned long)warm_cycles,
    (unsigned long)(rate / 1000), (unsigned long)(rate % 1000),
#if EVU_SOFTMAX_DRAIN_DIAG
    (unsigned long)(diag_before - diag_start), (unsigned long)(diag_after - diag_start),
    (unsigned long)(diag_after - diag_before), (unsigned long)empty_drain,
#endif
#if EVU_SOFTMAX_CHECK
    score_n12, state_n12,
#endif
    (unsigned long)start_cycle, (unsigned long)end_cycle, (unsigned long)flags, failures);
  return failures != 0;
}

__attribute__((used, noinline)) static int online_softmax_test_main(void) {
  __asm__ volatile ("csrw frm, x0\ncsrw fflags, x0" ::: "memory");
  if (XV_CSR_READ(XV_EVLENB) != 512 || !(online_scale > 0.0f) ||
      (float_bits(online_scale) & 0x7f800000u) == 0x7f800000u) {
    printf("[OS] FAIL require EVLEN=4096 and positive finite scale\n");
    return 1;
  }
#if OS_RESIDENT
  const unsigned seed_elements = 1;
#else
  const unsigned seed_elements = VL_MAX;
#endif
  for (unsigned i = 0; i < seed_elements; ++i) {
    union { uint32_t u; float f; } seed = { .u = 0xff7fffff };
    seeds[0][i] = seed.f;
    seeds[1][i] = 0.0f;
  }
#if EVU_SOFTMAX_REDUCE_VL != 128
  for (unsigned group = 0; group < 128 / EVU_SOFTMAX_REDUCE_VL - 1; ++group)
    for (unsigned i = 0; i < EVU_SOFTMAX_REDUCE_VL; ++i)
      fold_indices[group][i] = (group + 1) * EVU_SOFTMAX_REDUCE_VL + i;
#endif
#if EVU_SOFTMAX_CHECK
  initialize_reference();
#endif
  printf("[OS] r=%u c=%u stride=%u scale=" XV_STR(EVU_SOFTMAX_SCALE) " fp32 sched=" OS_SCHEDULE " state=%u loads4=%u red=%u w=%u check=%u diag=%u drain=1 fma=0\n",
    (unsigned)ROWS, (unsigned)COLS, (unsigned)STRIDE, (unsigned)EVU_SOFTMAX_STATE_BATCH,
    (unsigned)EVU_SOFTMAX_STATE_LOAD_BATCH,
    (unsigned)EVU_SOFTMAX_REDUCE_VL, (unsigned)EVU_SOFTMAX_WARMUP,
    (unsigned)EVU_SOFTMAX_CHECK, (unsigned)EVU_SOFTMAX_DRAIN_DIAG);
  initialize_input();
  for (unsigned first = 0; first <= 1; ++first) {
    if (EVU_SOFTMAX_FIRST >= 0 && first != (unsigned)EVU_SOFTMAX_FIRST) continue;
    if (run_case(first)) return 1;
  }
  printf(EVU_SOFTMAX_CHECK ? "[OS] PASS\n" : "[OS] DONE\n");
  return 0;
}

__attribute__((naked)) int main(void) {
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail online_softmax_test_main\n");
}
