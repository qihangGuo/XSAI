#ifndef XSAI_EVU_INTRINSICS_H
#define XSAI_EVU_INTRINSICS_H

#include <stdint.h>

#define XV_STR_1(x) #x
#define XV_STR(x) XV_STR_1(x)
#define XV_XREG_1(x) "x" #x
#define XV_XREG(x) XV_XREG_1(x)

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

/*
 * Launch encoding:
 *   rs1       scalar destination base address
 *   rs2       EVU register holding scaleA[0:127]
 *   rd field  EVU register holding scaleB[0:127]
 *   funct3=4  FP32 output, no fixed-point shift
 *
 * CUTE produces [N,M]. Each K-complete 8x8 microblock is converted as
 * float(C_int32) * scaleA[m] * scaleB[n] and stored directly through HBL2.
 */
#define XV_LAUNCH_FP32(out_base, scale_a_vr, scale_b_vr) do { \
  uintptr_t xv_out_ = (uintptr_t)(out_base); \
  __asm__ volatile (".insn r 0x0b, 4, 0x18, " XV_XREG(scale_b_vr) ", %0, " \
                    XV_XREG(scale_a_vr) :: "r"(xv_out_) : "memory"); \
} while (0)

/* WAIT checks the target command (job 0 here); FENCE drains the matrix stream
 * and publishes its accumulated FP flags/errors after the final write Ack. */
#define XV_WAIT() \
  __asm__ volatile (".insn r 0x0b, 0, 0x19, x0, x0, x0" ::: "memory")
#define XV_FENCE() \
  __asm__ volatile (".insn r 0x0b, 0, 0x1a, x0, x0, x0" ::: "memory")

#endif
