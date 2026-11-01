/* L1/HBL2 diagnostics, not an alternative softmax implementation.
 * Keep the production kernel and input contract in one source of truth. */
#define EVU_SOFTMAX_CHECK 0
#define EVU_SOFTMAX_DRAIN_DIAG 0
#define main mp_original_evu_main
#include "online_softmax_evu_test.c"
#undef main

#if EVU_SOFTMAX_ROWS != 32 || EVU_SOFTMAX_COLS != 256 || EVU_SOFTMAX_STRIDE != 256 || \
    EVU_SOFTMAX_STATE_BATCH || EVU_SOFTMAX_REDUCE_VL != 128
#error "memory diagnostics require the default 32x256, state=0, reduce_vl=128 kernel"
#endif

#define MP_ENGINE "EVU"
#define MP_NATIVE_VL 128
#ifndef MEM_PATH_RESIDENT_VL
#define MEM_PATH_RESIDENT_VL 128
#endif
#if MEM_PATH_RESIDENT_VL != 16 && MEM_PATH_RESIDENT_VL != 128
#error "EVU resident diagnostic VL must be 16 or 128"
#endif
#define MP_RESIDENT_VL MEM_PATH_RESIDENT_VL
static void mp_backend_init(void);
static unsigned mp_backend_check(void);
static void mp_backend_clear(void);
static unsigned mp_backend_flags(void);
static unsigned mp_backend_errors(void);
static void mp_backend_setup(unsigned id);
static void mp_backend_ready(unsigned id);
static void mp_backend_work(unsigned id);
static void mp_backend_finish(unsigned id);
static void mp_backend_snapshot(unsigned id);
static int mp_backend_mem_commands(unsigned id);
#include "vector_memory_path_bench.h"

static void mp_backend_init(void) {
  __asm__ volatile("csrw frm, x0\ncsrw fflags, x0" ::: "memory");
  seeds[0][0] = -0x1.fffffep127f;
  seeds[1][0] = 0.0f;
}

static unsigned mp_backend_check(void) {
  if (XV_CSR_READ(XV_EVLENB) == 512) return 0;
  printf("[MP-EVU] FAIL require VLEN=4096\n");
  return 1;
}

static void mp_backend_clear(void) { XV_CSR_WRITE(XV_EFFLAGS, 0); }
static unsigned mp_backend_flags(void) { return XV_CSR_READ(XV_EFFLAGS) & 0x1f; }
static unsigned mp_backend_errors(void) { return !!(XV_CSR_READ(XV_EVSTATUS) & 2); }

#define MP_ROWS(OP) OP(0, 16) OP(1, 17) OP(2, 18) OP(3, 19)
#define MP_LOAD_ROW(v, s) XV_LOAD(v, scores.data + (v) * MP_RESIDENT_VL, 2);
#define MP_MAX_ROW(v, s) XV_FREDUCE_MAX(s, 28, v, 2);
#define MP_SUM_ROW(v, s) XV_FREDUCE_SUM(s, 29, v, 2);
#define MP_EXP_ROW(v, s) XV_EXP2(v, v, 2);
#define MP_SHIFT_ROW(v, s) XV_FSUB_SCALAR(v, s, v);
#define MP_MUL_ROW(v, s) XV_FMUL_SCALAR(v, 30, v);

static void mp_backend_setup(unsigned id) {
  unsigned vl = mp_resident(id) ? MP_RESIDENT_VL :
    id == 8 ? 4 : id == 9 || id == 14 ? 16 : MP_NATIVE_VL;
  configure(vl);
  if (id == 5) XV_LOAD(0, mp_store_seed, 2);
  if (mp_resident(id)) {
    MP_ROWS(MP_LOAD_ROW)
    XV_LOAD_E0(28, &seeds[0][0]);
    XV_LOAD_E0(29, &seeds[1][0]);
    parameters[2] = 0x1.715476p0f;
    __asm__ volatile("fence rw, rw" ::: "memory");
    XV_LOAD_E0(30, &parameters[2]);
    if (id == 10) {
      /* exp2(exp2(x)) overflows after a few repetitions. Shift each row
       * into the nonpositive domain once, outside this EXP2/shift timer. */
      MP_ROWS(MP_MAX_ROW)
      MP_ROWS(MP_SHIFT_ROW)
    }
  }
}

static void mp_backend_ready(unsigned id) { (void)id; }

static void mp_backend_work(unsigned id) {
  if (id == 4 || id == 5 || id == 6 || id == 14) {
    const unsigned step = id == 14 ? 16 : MP_NATIVE_VL;
    for (unsigned i = 0; i < MP_FLOATS; i += step) {
      if (id != 5) XV_LOAD(0, scores.data + i, 2);
      if (id == 5 || id == 6) XV_STORE(0, mp_destination + i, 2);
    }
  } else if (id >= 7 && id <= 9) {
    const unsigned step = id == 7 ? 1 : id == 8 ? 4 : 16;
    for (unsigned i = 0; i < MP_STATE_FLOATS; i += step) {
      if (id == 7) {
        XV_LOAD_E0(0, scores.data + i);
        XV_STORE_E0(0, mp_destination + i);
      } else {
        XV_LOAD(0, scores.data + i, 2);
        XV_STORE(0, mp_destination + i, 2);
      }
    }
  } else {
    for (unsigned iteration = 0; iteration < MEM_PATH_ITERS; ++iteration) {
      if (id == 10) {
        /* Reset the sign without a memory operand; finite for every repeat. */
        MP_ROWS(MP_SHIFT_ROW)
        MP_ROWS(MP_EXP_ROW)
      } else if (id == 11) { MP_ROWS(MP_MAX_ROW) }
      else if (id == 12) { MP_ROWS(MP_SUM_ROW) }
      else {
        MP_ROWS(MP_MAX_ROW)
        MP_ROWS(MP_SHIFT_ROW)
        MP_ROWS(MP_MUL_ROW)
        MP_ROWS(MP_EXP_ROW)
        MP_ROWS(MP_SUM_ROW)
      }
    }
  }
}

static void mp_backend_snapshot(unsigned id) {
  if (id == 11 || id == 12 || id == 13) XV_STORE_E0(16, mp_snapshot);
  else XV_STORE_E0(0, mp_snapshot);
}

static inline void mp_backend_finish(unsigned id) {
  (void)id; /* XV_FENCE in the shared timer drains all EVU arithmetic too. */
}

static int mp_backend_mem_commands(unsigned id) {
  /* Kernel variants can change without changing this standalone diagnostic. */
  if (id <= 3) return -1;
  if (id == 4 || id == 5) return MP_FLOATS / MP_NATIVE_VL;
  if (id == 6) return 2 * MP_FLOATS / MP_NATIVE_VL;
  if (id == 14) return MP_FLOATS / 16;
  if (id >= 7 && id <= 9) return 2 * MP_STATE_FLOATS / (id == 7 ? 1 : id == 8 ? 4 : 16);
  return 0;
}

__attribute__((naked)) int main(void) {
  __asm__ volatile("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail mp_test_main\n");
}
