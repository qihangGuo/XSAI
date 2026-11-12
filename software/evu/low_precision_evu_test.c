#include <klib.h>
#include <stdint.h>
#include "evu_csr.h"

enum { REG_BYTES = 512, GUARD = 64 };
#define ALIGN __attribute__((aligned(64)))
static uint32_t source[128] ALIGN;
static uint8_t initial[REG_BYTES] ALIGN;
static uint8_t expected[REG_BYTES] ALIGN;
static uint32_t factor ALIGN;
static struct { uint8_t before[GUARD]; uint8_t data[REG_BYTES]; uint8_t after[GUARD]; } output ALIGN;
static const uint32_t fp32[8] = {0, 0x3e800000, 0x3f400000, 0x3f800000,
  0x40200000, 0x40a00000, 0x40c00000, 0xbf800000};
static const uint8_t int8[8] = {0, 0, 1, 1, 2, 5, 6, 255};
static const uint8_t e4m3[8] = {0, 0x28, 0x34, 0x38, 0x42, 0x4a, 0x4c, 0xb8};
static const uint8_t e5m2[8] = {0, 0x34, 0x3a, 0x3c, 0x41, 0x45, 0x46, 0xbc};
static const uint8_t half_e4m3[8] = {0, 0x20, 0x2c, 0x30, 0x3a, 0x42, 0x44, 0xb0};
static const uint8_t e2m1[8] = {0, 0, 2, 2, 4, 6, 7, 10};
static const uint8_t half_e2m1[8] = {0, 0, 1, 1, 2, 4, 5, 9};

static void drain(void) {
  XV_FENCE();
  __asm__ volatile ("fence rw, rw" ::: "memory");
}

__attribute__((used, noinline)) static int low_precision_main(void) {
  if (!(XV_CSR_READ(XV_EVCAP) & XV_CAP_LOW_PRECISION) || XV_CSR_READ(XV_EVLENB) != REG_BYTES) {
    printf("[LOWP] FAIL missing low precision capability\n");
    return 1;
  }
  const unsigned lengths[] = {0, 1, 2, 15, 16, 17, 63, 64, 65, 127, 128};
  memset(initial, 0xa5, sizeof(initial));
  memset(output.before, 0xa5, GUARD);
  memset(output.after, 0x5a, GUARD);
  unsigned cases = 0;
  for (unsigned operation = 0; operation < 6; ++operation) {
    const unsigned modes = operation >= 4 ? 2 : 1;
    for (unsigned mode = 0; mode < modes; ++mode) {
      factor = 127 + mode;
      for (unsigned element = 0; element < 128; ++element) source[element] = fp32[element % 8];
      if (operation == 3)
        for (unsigned element = 0; element < 128; ++element) ((uint8_t *)source)[element] = e4m3[element % 8];
      for (unsigned length = 0; length < sizeof(lengths) / sizeof(lengths[0]); ++length) {
        const unsigned vl = lengths[length];
        memcpy(expected, initial, sizeof(expected));
        for (unsigned element = 0; element < vl; ++element) {
          const unsigned index = element % 8;
          if (operation == 3) memcpy(expected + element * 4, &fp32[index], 4);
          else if (operation == 5) {
            const uint8_t nibble = mode ? half_e2m1[index] : e2m1[index];
            if (!(element & 1)) expected[element / 2] = nibble;
            else expected[element / 2] |= nibble << 4;
          } else expected[element] = operation == 0 ? int8[index] : operation == 2 ? e5m2[index] :
            operation == 4 && mode ? half_e4m3[index] : e4m3[index];
        }
        __asm__ volatile ("fence rw, rw" ::: "memory");
        (void)xv_setvli(REG_BYTES, 0);
        XV_LOAD(4, source, 0);
        XV_LOAD(8, initial, 0);
        (void)xv_setvli(1, 0x10);
        XV_LOAD_E0(12, &factor);
        drain();
        XV_CSR_WRITE(XV_EFFLAGS, 0);
        XV_CSR_WRITE(XV_EVXSAT, 0);
        (void)xv_setvli(vl, 0x10);
        switch (operation) {
          case 0: XV_CVT_I8_F32(8, 4); break;
          case 1: XV_CVT_E4M3_F32(8, 4); break;
          case 2: XV_CVT_E5M2_F32(8, 4); break;
          case 3: XV_CVT_F32_E4M3(8, 4); break;
          case 4: XV_CVT_MXFP8_F32(8, 12, 4); break;
          case 5: XV_CVT_MXFP4_F32(8, 12, 4); break;
        }
        drain();
        const uint64_t config = XV_CSR_READ(XV_EVCFG);
        const unsigned wanted_flags = vl >= 2 ? (operation == 0 ? 1 : operation == 5 ? 3 : 0) : 0;
        unsigned errors = XV_CSR_READ(XV_EFFLAGS) != wanted_flags || XV_CSR_READ(XV_EVXSAT) != 0;
        errors += (uint32_t)config != vl || ((config >> 32) & 255) != 16;
        (void)xv_setvli(REG_BYTES, 0);
        XV_STORE(8, output.data, 0);
        drain();
        errors += memcmp(output.data, expected, REG_BYTES) != 0;
        errors += !!(XV_CSR_READ(XV_EVSTATUS) & 2);
        for (unsigned index = 0; index < GUARD; ++index)
          errors += output.before[index] != 0xa5 || output.after[index] != 0x5a;
        if (errors) {
          printf("[LOWP] FAIL op=%u mode=%u vl=%u flags=%lu errors=%u\n", operation, mode, vl,
            (unsigned long)XV_CSR_READ(XV_EFFLAGS), errors);
          return 1;
        }
        ++cases;
      }
    }
  }
  printf("[LOWP] PASS cases=%u ops=6\n", cases);
  return 0;
}

__attribute__((naked)) int main(void) {
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail low_precision_main\n");
}
