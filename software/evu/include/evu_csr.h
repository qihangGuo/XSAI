#ifndef XSAI_EVU_CSR_H
#define XSAI_EVU_CSR_H

#include "evu_intrinsics.h"

/* Independent EVU CSR ABI 1, RV64. Core fcsr/vcsr/vstart are unrelated. */
#define XV_EVCFG 0x8c0
#define XV_EFFLAGS 0x8c1
#define XV_EVXSAT 0x8c2
#define XV_EQDESC0 0x8d0
#define XV_MEVSTATE 0xbc8
#define XV_EVLENB 0xcd0
#define XV_EVCAP 0xcd1
#define XV_EVSTATUS 0xcd2
#define XV_EVCAUSE 0xcd3
#define XV_EVEPC 0xcd4
#define XV_EVTVAL 0xcd5
#define XV_ALLOW_U 4
#define XV_ALLOW_S 8
#define XV_ERROR_ACK 256
#define XV_CONFIGURED (UINT64_C(1) << 40)
#define XV_CSR_READ(addr) ({ uint64_t xv_csr_value_; \
  __asm__ volatile ("csrr %0, " XV_STR(addr) : "=r"(xv_csr_value_) :: "memory"); xv_csr_value_; })
#define XV_CSR_WRITE(addr, value) do { uint64_t xv_csr_value_ = (uint64_t)(value); \
  __asm__ volatile ("csrw " XV_STR(addr) ", %0" :: "r"(xv_csr_value_) : "memory"); } while (0)

/* M-mode only. U/S also require mstateen0.C, and U additionally sstateen0.C.
 * Set Initial only if Off; never relabel an existing Dirty context as clean. */
static inline void xv_enable_m(unsigned access) {
  uint64_t value = XV_CSR_READ(XV_MEVSTATE);
  XV_CSR_WRITE(XV_MEVSTATE, (value & 3 ? value : value | 1) | (access & 12));
}

/* Standard RVV VTYPE low byte: e8/e16/e32, m1/m2/m4/m8 or mf2/mf4/mf8.
 * AVL zero means empty. UINT32_MAX requests VLMAX. Rounding is fixed RNE. */
static inline uint32_t xv_setvl(uint32_t avl, uint8_t vtype) {
  XV_CSR_WRITE(XV_EVCFG, XV_CONFIGURED | ((uint64_t)vtype << 32) | avl);
  return (uint32_t)XV_CSR_READ(XV_EVCFG);
}

static inline uint64_t xv_descriptor(unsigned scale, unsigned bias, int32_t zero,
                                      unsigned shift, unsigned output_sew) {
  return (UINT64_C(1) << 50) | ((uint64_t)(output_sew & 3) << 48) |
    ((uint64_t)(shift & 63) << 42) | ((uint64_t)(uint32_t)zero << 10) |
    ((uint64_t)(bias & 31) << 5) | (scale & 31);
}

/* Diagnostic error registers describe the first error since ACK. ACK clears
 * only the record; a failed operation can have partial writes and must not be retried blindly. */
typedef struct {
  uint64_t vr[32][64] __attribute__((aligned(64)));
  uint64_t cfg, fflags, vxsat, state, descriptors[8];
  uint64_t status, cause, pc, tval;
} xv_context;

/* ABI 1 context helpers require VLEN=4096 and M-mode. Return -1 for an
 * unsupported/disabled context, -2 for an error requiring software handling.
 * CSR reads drain all old work before changing the temporary save configuration.
 * Read-only error records are copied for diagnosis, not restored into hardware. */
static inline int xv_context_save(xv_context *ctx) {
  if (XV_CSR_READ(XV_EVLENB) != sizeof(ctx->vr[0])) return -1;
  ctx->state = XV_CSR_READ(XV_MEVSTATE);
  if (!(ctx->state & 3)) return -1;
  ctx->status = XV_CSR_READ(XV_EVSTATUS);
  ctx->cause = XV_CSR_READ(XV_EVCAUSE);
  ctx->pc = XV_CSR_READ(XV_EVEPC);
  ctx->tval = XV_CSR_READ(XV_EVTVAL);
  if (ctx->status & 2) return -2;
  ctx->cfg = XV_CSR_READ(XV_EVCFG);
  ctx->fflags = XV_CSR_READ(XV_EFFLAGS);
  ctx->vxsat = XV_CSR_READ(XV_EVXSAT);
  ctx->descriptors[0] = XV_CSR_READ(0x8d0);
  ctx->descriptors[1] = XV_CSR_READ(0x8d1);
  ctx->descriptors[2] = XV_CSR_READ(0x8d2);
  ctx->descriptors[3] = XV_CSR_READ(0x8d3);
  ctx->descriptors[4] = XV_CSR_READ(0x8d4);
  ctx->descriptors[5] = XV_CSR_READ(0x8d5);
  ctx->descriptors[6] = XV_CSR_READ(0x8d6);
  ctx->descriptors[7] = XV_CSR_READ(0x8d7);
  xv_setvl(512, 0);
  XV_STORE(0, ctx->vr[0], 0);
  XV_STORE(1, ctx->vr[1], 0);
  XV_STORE(2, ctx->vr[2], 0);
  XV_STORE(3, ctx->vr[3], 0);
  XV_STORE(4, ctx->vr[4], 0);
  XV_STORE(5, ctx->vr[5], 0);
  XV_STORE(6, ctx->vr[6], 0);
  XV_STORE(7, ctx->vr[7], 0);
  XV_STORE(8, ctx->vr[8], 0);
  XV_STORE(9, ctx->vr[9], 0);
  XV_STORE(10, ctx->vr[10], 0);
  XV_STORE(11, ctx->vr[11], 0);
  XV_STORE(12, ctx->vr[12], 0);
  XV_STORE(13, ctx->vr[13], 0);
  XV_STORE(14, ctx->vr[14], 0);
  XV_STORE(15, ctx->vr[15], 0);
  XV_STORE(16, ctx->vr[16], 0);
  XV_STORE(17, ctx->vr[17], 0);
  XV_STORE(18, ctx->vr[18], 0);
  XV_STORE(19, ctx->vr[19], 0);
  XV_STORE(20, ctx->vr[20], 0);
  XV_STORE(21, ctx->vr[21], 0);
  XV_STORE(22, ctx->vr[22], 0);
  XV_STORE(23, ctx->vr[23], 0);
  XV_STORE(24, ctx->vr[24], 0);
  XV_STORE(25, ctx->vr[25], 0);
  XV_STORE(26, ctx->vr[26], 0);
  XV_STORE(27, ctx->vr[27], 0);
  XV_STORE(28, ctx->vr[28], 0);
  XV_STORE(29, ctx->vr[29], 0);
  XV_STORE(30, ctx->vr[30], 0);
  XV_STORE(31, ctx->vr[31], 0);
  XV_FENCE();
  ctx->status = XV_CSR_READ(XV_EVSTATUS);
  if (ctx->status & 2) {
    ctx->cause = XV_CSR_READ(XV_EVCAUSE);
    ctx->pc = XV_CSR_READ(XV_EVEPC);
    ctx->tval = XV_CSR_READ(XV_EVTVAL);
    XV_CSR_WRITE(XV_EVCFG, ctx->cfg);
    return -2;
  }
  XV_CSR_WRITE(XV_EVCFG, ctx->cfg);
  XV_CSR_WRITE(XV_EFFLAGS, ctx->fflags);
  XV_CSR_WRITE(XV_EVXSAT, ctx->vxsat);
  XV_CSR_WRITE(XV_MEVSTATE, ctx->state);
  return 0;
}

static inline int xv_context_restore(const xv_context *ctx) {
  if (!(ctx->state & 3) || XV_CSR_READ(XV_EVLENB) != sizeof(ctx->vr[0])) return -1;
  if (ctx->status & 2) return -2;
  xv_enable_m(0);
  if (XV_CSR_READ(XV_EVSTATUS) & 2) return -2;
  xv_setvl(512, 0);
  XV_LOAD(0, ctx->vr[0], 0);
  XV_LOAD(1, ctx->vr[1], 0);
  XV_LOAD(2, ctx->vr[2], 0);
  XV_LOAD(3, ctx->vr[3], 0);
  XV_LOAD(4, ctx->vr[4], 0);
  XV_LOAD(5, ctx->vr[5], 0);
  XV_LOAD(6, ctx->vr[6], 0);
  XV_LOAD(7, ctx->vr[7], 0);
  XV_LOAD(8, ctx->vr[8], 0);
  XV_LOAD(9, ctx->vr[9], 0);
  XV_LOAD(10, ctx->vr[10], 0);
  XV_LOAD(11, ctx->vr[11], 0);
  XV_LOAD(12, ctx->vr[12], 0);
  XV_LOAD(13, ctx->vr[13], 0);
  XV_LOAD(14, ctx->vr[14], 0);
  XV_LOAD(15, ctx->vr[15], 0);
  XV_LOAD(16, ctx->vr[16], 0);
  XV_LOAD(17, ctx->vr[17], 0);
  XV_LOAD(18, ctx->vr[18], 0);
  XV_LOAD(19, ctx->vr[19], 0);
  XV_LOAD(20, ctx->vr[20], 0);
  XV_LOAD(21, ctx->vr[21], 0);
  XV_LOAD(22, ctx->vr[22], 0);
  XV_LOAD(23, ctx->vr[23], 0);
  XV_LOAD(24, ctx->vr[24], 0);
  XV_LOAD(25, ctx->vr[25], 0);
  XV_LOAD(26, ctx->vr[26], 0);
  XV_LOAD(27, ctx->vr[27], 0);
  XV_LOAD(28, ctx->vr[28], 0);
  XV_LOAD(29, ctx->vr[29], 0);
  XV_LOAD(30, ctx->vr[30], 0);
  XV_LOAD(31, ctx->vr[31], 0);
  XV_FENCE();
  if (XV_CSR_READ(XV_EVSTATUS) & 2) return -2;
  XV_CSR_WRITE(XV_EVCFG, ctx->cfg);
  XV_CSR_WRITE(0x8d0, ctx->descriptors[0]);
  XV_CSR_WRITE(0x8d1, ctx->descriptors[1]);
  XV_CSR_WRITE(0x8d2, ctx->descriptors[2]);
  XV_CSR_WRITE(0x8d3, ctx->descriptors[3]);
  XV_CSR_WRITE(0x8d4, ctx->descriptors[4]);
  XV_CSR_WRITE(0x8d5, ctx->descriptors[5]);
  XV_CSR_WRITE(0x8d6, ctx->descriptors[6]);
  XV_CSR_WRITE(0x8d7, ctx->descriptors[7]);
  XV_CSR_WRITE(XV_EFFLAGS, ctx->fflags);
  XV_CSR_WRITE(XV_EVXSAT, ctx->vxsat);
  XV_CSR_WRITE(XV_MEVSTATE, ctx->state);
  return 0;
}

#endif
