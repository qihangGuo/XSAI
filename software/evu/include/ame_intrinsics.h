#ifndef __RISCV64_AME_H__
#define __RISCV64_AME_H__

/* ===== AME Type Configuration ===== */

/*
 * The XSAI/NEMU matrix model uses mcfg0..mcfg7 to describe the element type
 * of each tile/accumulator register.  LLVM's matrix-xuantie-0.6 assembler does
 * not currently expose the msetcfg mnemonic, but it accepts the underlying
 * custom instruction through .insn.  Keep this encoding here so AME kernels
 * configure the same types on both NEMU and CUTE.
 */
#define AME_CONF_INT8 0x2
#define AME_CONF_INT32 0x4
#define AME_CONF_FP32 0xc

#define MSETCFG(MCFG_RD, VALUE)                                                \
  do {                                                                         \
    long _mcfg_value = (long)(VALUE);                                          \
    asm volatile(".insn r 0x2b, 0, 0x21, " #MCFG_RD ", %0, x0"                 \
                 :                                                             \
                 : "r"(_mcfg_value)                                            \
                 : "memory");                                                  \
  } while (0)

#define AME_SET_F32()                                                          \
  do {                                                                         \
    MSETCFG(x0, AME_CONF_FP32);                                                \
    MSETCFG(x1, AME_CONF_FP32);                                                \
    MSETCFG(x4, AME_CONF_FP32);                                                \
  } while (0)

#define AME_SET_I8()                                                           \
  do {                                                                         \
    MSETCFG(x0, AME_CONF_INT8);                                                \
    MSETCFG(x1, AME_CONF_INT8);                                                \
    MSETCFG(x4, AME_CONF_INT32);                                               \
  } while (0)

/* ===== Hardware Tile Limits (TRLEN=512, ROWNUM=128) ===== */

#define AME_TRLEN 512
#define AME_ROWNUM 128
#define AME_TMMAX AME_ROWNUM
#define AME_TNMAX AME_ROWNUM
#define AME_TKMAX_8 (AME_TRLEN / 8)   // 64
#define AME_TKMAX_32 (AME_TRLEN / 32) // 16

/* CPU stores that prepare a tile must be visible before CUTE loads it. */
#ifndef AME_MLOAD_FENCE
#define AME_MLOAD_FENCE 1
#endif

/* ===== Tile Size Configuration ===== */

#define MSETTILEM(RD, VAL)                                                     \
  do {                                                                         \
    /* custom-1, funct3=0, funct7=0x11, rs1=VAL, rd=rs2=x0 */                 \
    asm volatile(".insn r 0x2b, 0, 0x11, x0, %0, x0" : : "r"(VAL) :         \
                 "memory");                                                    \
    (RD) = VAL;                                                                \
  } while (0)

#define MSETTILEK(RD, VAL)                                                     \
  do {                                                                         \
    /* custom-1, funct3=0, funct7=0x09, rs1=VAL, rd=rs2=x0 */                 \
    asm volatile(".insn r 0x2b, 0, 0x09, x0, %0, x0" : : "r"(VAL) :         \
                 "memory");                                                    \
    (RD) = VAL;                                                                \
  } while (0)

#define MSETTILEN(RD, VAL)                                                     \
  do {                                                                         \
    /* custom-1, funct3=0, funct7=0x19, rs1=VAL, rd=rs2=x0 */                 \
    asm volatile(".insn r 0x2b, 0, 0x19, x0, %0, x0" : : "r"(VAL) :         \
                 "memory");                                                    \
    (RD) = VAL;                                                                \
  } while (0)

/* ===== Unified matrix operations ===== */

/*
 * The XSAI RTL and NEMU use the unified proposal-12 encoding, where element
 * types come from mcfg.  The LLVM matrix-xuantie-0.6 mnemonics encode element
 * widths in a different instruction format, so use raw instructions here.
 */
#define AME_GPR_tr0 x0
#define AME_GPR_tr1 x1
#define AME_GPR_tr2 x2
#define AME_GPR_tr3 x3
#define AME_GPR_acc0 x4
#define AME_GPR_acc1 x5
#define AME_GPR_acc2 x6
#define AME_GPR_acc3 x7
#define AME_REG_(REG) AME_GPR_##REG
#define AME_REG(REG) AME_REG_(REG)
#define AME_STRINGIFY_(VALUE) #VALUE
#define AME_STRINGIFY(VALUE) AME_STRINGIFY_(VALUE)

/* Synchronization group (funct3=100) in the same unified encoding. */
#define MSYNCRESET()                                                           \
  asm volatile(".insn r 0x2b, 4, 64, x0, x0, x0" ::: "memory")

#define MRELEASE()                                                             \
  asm volatile(".insn r 0x2b, 4, 72, x0, x0, x0" ::: "memory")

#define MACQUIRE(VALUE)                                                        \
  asm volatile(".insn r 0x2b, 4, 80, x0, %0, x0" : : "r"(VALUE) : "memory")

#define MZERO_ACC(ACC)                                                         \
  asm volatile(".insn r 0x2b, 3, 0, " AME_STRINGIFY(AME_REG(ACC))             \
               ", x0, x0" ::: "memory")

/* ===== FP32 Load/Store ===== */

#define MLAE32(REG, SRC, STRIDE)                                               \
  asm volatile(".insn r 0x2b, 1, 0, " AME_STRINGIFY(AME_REG(REG))             \
               ", %0, %1" ::"r"(SRC), "r"(STRIDE) : "memory")

#define MLBE32(REG, SRC, STRIDE)                                               \
  asm volatile(".insn r 0x2b, 1, 8, " AME_STRINGIFY(AME_REG(REG))             \
               ", %0, %1" ::"r"(SRC), "r"(STRIDE) : "memory")

#define MLCE32(REG, SRC, STRIDE)                                               \
  asm volatile(".insn r 0x2b, 1, 16, " AME_STRINGIFY(AME_REG(REG))            \
               ", %0, %1" ::"r"(SRC), "r"(STRIDE) : "memory")

#define MSCE32(REG, DST, STRIDE)                                               \
  asm volatile(".insn r 0x2b, 1, 17, " AME_STRINGIFY(AME_REG(REG))            \
               ", %0, %1" ::"r"(DST), "r"(STRIDE) : "memory")

/* ===== INT8 Load/Store ===== */

#define MLAE8(REG, SRC, STRIDE) MLAE32(REG, SRC, STRIDE)

#define MLBE8(REG, SRC, STRIDE) MLBE32(REG, SRC, STRIDE)

/* ===== Matrix Multiply-Accumulate ===== */

#define MFMA_F(ACC, TR0, TR1)                                                  \
  asm volatile(".insn r 0x2b, 2, 0, " AME_STRINGIFY(AME_REG(ACC))             \
               ", " AME_STRINGIFY(AME_REG(TR0)) ", "                         \
               AME_STRINGIFY(AME_REG(TR1)) ::: "memory")

#define MQMA(ACC, TR0, TR1) MFMA_F(ACC, TR0, TR1)

#endif /* __RISCV64_AME_H__ */
