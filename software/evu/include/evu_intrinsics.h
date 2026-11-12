#ifndef XSAI_EVU_INTRINSICS_H
#define XSAI_EVU_INTRINSICS_H

#include <stdint.h>

#define XV_STR_1(x) #x
#define XV_STR(x) XV_STR_1(x)
#define XV_XREG_1(x) "x" #x
#define XV_XREG(x) XV_XREG_1(x)

/* ABI 2 configuration: unsigned AVL, RVV low-byte VTYPE encoding, scalar rd=VL.
 * rs1=x0 means AVL zero; UINT64_MAX requests VLMAX. No implicit keep-VL form.
 */
static inline uint32_t xv_setvl(uint64_t avl, uint64_t vtype) {
  uint64_t actual;
  __asm__ volatile (".insn r 0x0b, 7, 0x46, %0, %1, %2"
                    : "=r"(actual) : "r"(avl), "r"(vtype) : "memory");
  return (uint32_t)actual;
}

/* funct3 carries VTYPE[7:5], the I-immediate carries funct7 and VTYPE[4:0]. */
#define xv_setvli(avl, type) ({ \
  _Static_assert(((type) & ~255u) == 0, "xv_setvli requires an 8-bit constant VTYPE"); \
  uint64_t xv_avl_ = (uint64_t)(avl), xv_actual_; \
  __asm__ volatile (".insn i 0x0b, %2, %0, %1, %3" \
                    : "=r"(xv_actual_) : "r"(xv_avl_), "i"(((type) >> 5) & 7), \
                      "i"(-1888 + ((type) & 31)) : "memory"); \
  (uint32_t)xv_actual_; \
})

/* EVU register indices are compile-time constants in the range 0..31. */
#define XV_LOAD(vd, base, sew) do { \
  uintptr_t xv_base_ = (uintptr_t)(base); \
  __asm__ volatile (".insn r 0x0b, " XV_STR(sew) ", 0x10, " XV_XREG(vd) ", %0, x0" \
                    :: "r"(xv_base_) : "memory"); \
} while (0)

#define XV_STORE(vs, base, sew) do { \
  uintptr_t xv_base_ = (uintptr_t)(base); \
  __asm__ volatile (".insn r 0x0b, " XV_STR(sew) ", 0x11, " XV_XREG(vs) ", %0, x0" \
                    :: "r"(xv_base_) : "memory"); \
} while (0)

#define XV_BINARY(funct7, vd, vs1, vs2, sew) \
  __asm__ volatile (".insn r 0x0b, " XV_STR(sew) ", " XV_STR(funct7) ", " \
                    XV_XREG(vd) ", " XV_XREG(vs1) ", " XV_XREG(vs2) ::: "memory")

/* IEEE FP16 (sew=1) or FP32 (sew=2); BF16 is not enabled. */
#define XV_EXP2(vd, vs, sew) XV_BINARY(0x0f, vd, 0, vs, sew)

/* Binary results are vs2 op vs1, including subtraction and division. */
#define XV_FADD(vd, vs1, vs2, sew) XV_BINARY(0x30, vd, vs1, vs2, sew)
#define XV_FSUB(vd, vs1, vs2, sew) XV_BINARY(0x31, vd, vs1, vs2, sew)
#define XV_FMUL(vd, vs1, vs2, sew) XV_BINARY(0x32, vd, vs1, vs2, sew)
#define XV_FDIV(vd, vs1, vs2, sew) XV_BINARY(0x34, vd, vs1, vs2, sew)
#define XV_FMAX(vd, vs1, vs2, sew) XV_BINARY(0x36, vd, vs1, vs2, sew)

/* Reduce vs2 with the seed in vs1[0]; only vd[0] holds the result. */
#define XV_FREDUCE_SUM(vd, vs1, vs2, sew) XV_BINARY(0x37, vd, vs1, vs2, sew)
#define XV_FREDUCE_MAX(vd, vs1, vs2, sew) XV_BINARY(0x44, vd, vs1, vs2, sew)
/* vd[i] = vs2[vs1[i]], with integer element indices in vs1. */
#define XV_GATHER(vd, vs1, vs2, sew) XV_BINARY(0x47, vd, vs1, vs2, sew)
/* Unsigned five-bit index. Index zero broadcasts vs2[0] one DLEN group at a time. */
#define XV_GATHER_IMM(vd, index, vs2, sew) XV_BINARY(0x51, vd, index, vs2, sew)
#define XV_BROADCAST(vd, vs2, sew) XV_GATHER_IMM(vd, 0, vs2, sew)

#define XV_SLIDEUP(vd, offset, vs, sew) do { \
  uint64_t xv_offset_ = (uint64_t)(offset); \
  __asm__ volatile (".insn r 0x0b, " XV_STR(sew) ", 0x3e, " XV_XREG(vd) ", %0, " XV_XREG(vs) \
                    :: "r"(xv_offset_) : "memory"); \
} while (0)
#define XV_SLIDEDOWN(vd, offset, vs, sew) do { \
  uint64_t xv_offset_ = (uint64_t)(offset); \
  __asm__ volatile (".insn r 0x0b, " XV_STR(sew) ", 0x3f, " XV_XREG(vd) ", %0, " XV_XREG(vs) \
                    :: "r"(xv_offset_) : "memory"); \
} while (0)

/* FP32 m1 forms leave global VL unchanged. E0 writes only element zero;
 * scalar-source forms use vs1[0] for every active vs2 element. */
#define XV_LOAD_E0(vd, base) do { \
  uintptr_t xv_base_ = (uintptr_t)(base); \
  __asm__ volatile (".insn r 0x0b, 2, 0x52, " XV_XREG(vd) ", %0, x0" \
                    :: "r"(xv_base_) : "memory"); \
} while (0)
#define XV_STORE_E0(vs, base) do { \
  uintptr_t xv_base_ = (uintptr_t)(base); \
  __asm__ volatile (".insn r 0x0b, 2, 0x53, " XV_XREG(vs) ", %0, x0" \
                    :: "r"(xv_base_) : "memory"); \
} while (0)
#define XV_FADD_E0(vd, vs1, vs2) XV_BINARY(0x54, vd, vs1, vs2, 2)
#define XV_FSUB_E0(vd, vs1, vs2) XV_BINARY(0x55, vd, vs1, vs2, 2)
#define XV_FMUL_E0(vd, vs1, vs2) XV_BINARY(0x56, vd, vs1, vs2, 2)
#define XV_FDIV_E0(vd, vs1, vs2) XV_BINARY(0x57, vd, vs1, vs2, 2)
#define XV_FMAX_E0(vd, vs1, vs2) XV_BINARY(0x58, vd, vs1, vs2, 2)
#define XV_EXP2_E0(vd, vs) XV_BINARY(0x59, vd, 0, vs, 2)
#define XV_FADD_SCALAR(vd, vs1, vs2) XV_BINARY(0x5a, vd, vs1, vs2, 2)
#define XV_FSUB_SCALAR(vd, vs1, vs2) XV_BINARY(0x5b, vd, vs1, vs2, 2)
#define XV_FMUL_SCALAR(vd, vs1, vs2) XV_BINARY(0x5c, vd, vs1, vs2, 2)
#define XV_FMAX_SCALAR(vd, vs1, vs2) XV_BINARY(0x5d, vd, vs1, vs2, 2)
#define XV_FSQRT(vd, vs, sew) XV_BINARY(0x60, vd, 0, vs, sew)
#define XV_FRSQRT7(vd, vs, sew) XV_BINARY(0x61, vd, 0, vs, sew)
#define XV_FSQRT_E0(vd, vs) XV_BINARY(0x62, vd, 0, vs, 2)
#define XV_FRSQRT7_E0(vd, vs) XV_BINARY(0x63, vd, 0, vs, 2)
#define XV_FREC7(vd, vs, sew) XV_BINARY(0x64, vd, 0, vs, sew)
#define XV_FREC7_E0(vd, vs) XV_BINARY(0x65, vd, 0, vs, 2)
#define XV_FSGNJX(vd, vs1, vs2, sew) XV_BINARY(0x66, vd, vs1, vs2, sew)
#define XV_FABS(vd, vs, sew) XV_FSGNJX(vd, vs, vs, sew)
#define XV_FWCVT_F_F(vd, vs) XV_BINARY(0x67, vd, 0, vs, 1)
#define XV_FNCVT_F_F(vd, vs) XV_BINARY(0x68, vd, 0, vs, 1)
#define XV_FCVT_X_F(vd, vs) XV_BINARY(0x69, vd, 0, vs, 2)
#define XV_FCVT_F_X(vd, vs) XV_BINARY(0x6a, vd, 0, vs, 2)
#define XV_NCLIP_IMM(vd, vs, shift, sew) do { \
  _Static_assert((shift) >= 0 && (shift) < 32, "shift must fit uimm5"); \
  XV_BINARY(0x6b, vd, shift, vs, sew); \
} while (0)
#define XV_ZEXT2(vd, vs, sew) XV_BINARY(0x6c, vd, 0, vs, sew)
#define XV_SEXT2(vd, vs, sew) XV_BINARY(0x6d, vd, 0, vs, sew)
#define XV_CVT_I8_F32(vd, vs) XV_BINARY(0x70, vd, 0, vs, 2)
#define XV_CVT_E4M3_F32(vd, vs) XV_BINARY(0x71, vd, 0, vs, 2)
#define XV_CVT_E5M2_F32(vd, vs) XV_BINARY(0x72, vd, 0, vs, 2)
#define XV_CVT_F32_E4M3(vd, vs) XV_BINARY(0x73, vd, 0, vs, 2)
#define XV_CVT_MXFP8_F32(vd, scale, vs) XV_BINARY(0x74, vd, scale, vs, 2)
#define XV_CVT_MXFP4_F32(vd, scale, vs) XV_BINARY(0x75, vd, scale, vs, 2)
#define XV_NSRL_IMM(vd, vs, shift, sew) do { \
  _Static_assert((shift) >= 0 && (shift) < 32, "shift must fit uimm5"); \
  XV_BINARY(0x6e, vd, shift, vs, sew); \
} while (0)
/* 0x18, 0x19, 0x50 and 0x5e are retired and must trap, including with VL=0.
 * FENCE waits for all older EVU
 * work and the final physical write Ack. Each command publishes its own flags. */
#define XV_FENCE() \
  __asm__ volatile (".insn r 0x0b, 0, 0x1a, x0, x0, x0" ::: "memory")

#endif
