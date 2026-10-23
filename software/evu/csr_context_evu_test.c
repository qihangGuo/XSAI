#include <am.h>
#include <klib.h>
#include "evu_csr.h"

static xv_context saved, observed;
static uint32_t seed[32][128] __attribute__((aligned(64)));
static uint32_t one[128] __attribute__((aligned(64)));
static uint32_t three[128] __attribute__((aligned(64)));
static uint32_t result[128] __attribute__((aligned(64)));

static int csr_context_test_main(void) __attribute__((used, noinline));
static int csr_context_test_main(void) {
  uint64_t core_status, core_fcsr, core_start, core_sat, core_rm;
  __asm__ volatile ("csrr %0, mstatus" : "=r"(core_status));
  __asm__ volatile ("li t0, 0x50\ncsrw fcsr, t0\nli t0, 37\ncsrw vstart, t0\n"
                    "csrw vxsat, zero\nli t0, 3\ncsrw vxrm, t0" ::: "t0", "memory");
  for (unsigned reg = 0; reg < 32; ++reg)
    for (unsigned i = 0; i < 128; ++i) seed[reg][i] = 0x10000000u | (reg << 16) | i;
  for (unsigned i = 0; i < 128; ++i) { one[i] = 0x3f800000; three[i] = 0x40400000; }
  /* Execute EVU FP with both core contexts Off, core FRM=RDN, vstart=37. */
  __asm__ volatile ("li t0, 0x6600\ncsrc mstatus, t0" ::: "t0", "memory");
  xv_setvl(128, 0x10);
  XV_CSR_WRITE(XV_EFFLAGS, 0);
  XV_LOAD(1, one, 2);
  XV_LOAD(2, three, 2);
  XV_FDIV(3, 2, 1, 2);
  XV_STORE(3, result, 2);
  XV_FENCE();
  int ok = XV_CSR_READ(XV_EFFLAGS) == 1;
  for (unsigned i = 0; i < 128; ++i) ok &= result[i] == 0x3eaaaaab;
  XV_LOAD(0, seed[0], 2);
  XV_LOAD(1, seed[1], 2);
  XV_LOAD(2, seed[2], 2);
  XV_LOAD(3, seed[3], 2);
  XV_LOAD(4, seed[4], 2);
  XV_LOAD(5, seed[5], 2);
  XV_LOAD(6, seed[6], 2);
  XV_LOAD(7, seed[7], 2);
  XV_LOAD(8, seed[8], 2);
  XV_LOAD(9, seed[9], 2);
  XV_LOAD(10, seed[10], 2);
  XV_LOAD(11, seed[11], 2);
  XV_LOAD(12, seed[12], 2);
  XV_LOAD(13, seed[13], 2);
  XV_LOAD(14, seed[14], 2);
  XV_LOAD(15, seed[15], 2);
  XV_LOAD(16, seed[16], 2);
  XV_LOAD(17, seed[17], 2);
  XV_LOAD(18, seed[18], 2);
  XV_LOAD(19, seed[19], 2);
  XV_LOAD(20, seed[20], 2);
  XV_LOAD(21, seed[21], 2);
  XV_LOAD(22, seed[22], 2);
  XV_LOAD(23, seed[23], 2);
  XV_LOAD(24, seed[24], 2);
  XV_LOAD(25, seed[25], 2);
  XV_LOAD(26, seed[26], 2);
  XV_LOAD(27, seed[27], 2);
  XV_LOAD(28, seed[28], 2);
  XV_LOAD(29, seed[29], 2);
  XV_LOAD(30, seed[30], 2);
  XV_LOAD(31, seed[31], 2);
  XV_FENCE();
  xv_setvl(13, 0x0f); /* e16,mf2; save helpers must still preserve every VRF byte. */
  XV_CSR_WRITE(XV_EFFLAGS, 5);
  XV_CSR_WRITE(XV_EVXSAT, 1);
  XV_CSR_WRITE(0x8d0, xv_descriptor(0, 8, -17, 1, 2));
  XV_CSR_WRITE(0x8d1, xv_descriptor(1, 9, -17, 2, 2));
  XV_CSR_WRITE(0x8d2, xv_descriptor(2, 10, -17, 3, 2));
  XV_CSR_WRITE(0x8d3, xv_descriptor(3, 11, -17, 4, 2));
  XV_CSR_WRITE(0x8d4, xv_descriptor(4, 12, -17, 5, 2));
  XV_CSR_WRITE(0x8d5, xv_descriptor(5, 13, -17, 6, 2));
  XV_CSR_WRITE(0x8d6, xv_descriptor(6, 14, -17, 7, 2));
  XV_CSR_WRITE(0x8d7, xv_descriptor(7, 15, -17, 8, 2));
  if (xv_context_save(&saved)) { ok = 0; goto done; }
  xv_setvl(128, 0x10);
  XV_LOAD(0, three, 2);
  XV_FENCE();
  XV_CSR_WRITE(XV_EFFLAGS, 0);
  XV_CSR_WRITE(XV_EVXSAT, 0);
  XV_CSR_WRITE(XV_EQDESC0, 0);
  if (xv_context_restore(&saved) || xv_context_save(&observed)) { ok = 0; goto done; }
  ok &= saved.cfg == observed.cfg && saved.fflags == observed.fflags && saved.vxsat == observed.vxsat;
  ok &= memcmp(saved.vr, observed.vr, sizeof(saved.vr)) == 0;
  ok &= memcmp(saved.descriptors, observed.descriptors, sizeof(saved.descriptors)) == 0;
done:
  __asm__ volatile ("csrw mstatus, %0" :: "r"(core_status) : "memory");
  __asm__ volatile ("csrr %0, fcsr\ncsrr %1, vstart\ncsrr %2, vxsat\ncsrr %3, vxrm"
                    : "=r"(core_fcsr), "=r"(core_start), "=r"(core_sat), "=r"(core_rm));
  ok &= core_fcsr == 0x50 && core_start == 37 && core_sat == 0 && core_rm == 3;
  __asm__ volatile ("csrw vstart, zero" ::: "memory");
  printf("EVU CSR/context %s: fixed RNE, core FS/VS Off, isolated flags/start, 32 VRFs and descriptors\n", ok ? "PASS" : "FAIL");
  return ok ? 0 : 1;
}

int main(void) __attribute__((naked));
int main(void) {
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 1\ncsrw 0xbc8, t0\ntail csr_context_test_main\n");
}
