#include <am.h>
#include <klib.h>
#include <stdint.h>
#include "evu_intrinsics.h"

/* VLEN=4096. Check exact powers of two without a scalar floating-point reference. */
enum { VECTOR_BYTES = 512, GUARD_BYTES = 64, ROUNDS = 3 };

union Exp2Data {
  uint8_t u8[VECTOR_BYTES];
  uint16_t u16[VECTOR_BYTES / 2];
  uint32_t u32[VECTOR_BYTES / 4];
};

struct Exp2Buffer {
  uint8_t before[GUARD_BYTES];
  union Exp2Data data;
  uint8_t after[GUARD_BYTES];
} __attribute__((aligned(64)));

static volatile struct Exp2Buffer buffers[3];
static const uint16_t input16[] = {0, 0x3c00, 0x4000, 0xbc00, 0x4200, 0xc000, 0x4400, 0xc200};
static const uint16_t result16[] = {0x3c00, 0x4000, 0x4400, 0x3800, 0x4800, 0x3400, 0x4c00, 0x3000};
static const uint32_t input32[] = {
  0, 0x3f800000, 0x40000000, 0xbf800000, 0x40400000, 0xc0000000, 0x40800000, 0xc0400000
};
static const uint32_t result32[] = {
  0x3f800000, 0x40000000, 0x40800000, 0x3f000000, 0x41000000, 0x3e800000, 0x41800000, 0x3e000000
};

static void initialize(unsigned sew, unsigned round)
{
  for (unsigned b = 0; b < 3; ++b) {
    for (unsigned i = 0; i < GUARD_BYTES; ++i) {
      buffers[b].before[i] = 0xa5;
      buffers[b].after[i] = 0x5a;
    }
    for (unsigned i = 0; i < VECTOR_BYTES; ++i) buffers[b].data.u8[i] = 0xcc;
  }
  for (unsigned i = 0; i < (VECTOR_BYTES >> sew); ++i) {
    const unsigned index = (i + round * 3) % 8;
    if (sew == 1) buffers[0].data.u16[i] = input16[index];
    else buffers[0].data.u32[i] = input32[index];
  }
}

static int check(unsigned sew, unsigned round)
{
  for (unsigned b = 0; b < 3; ++b) {
    for (unsigned i = 0; i < GUARD_BYTES; ++i) {
      if (buffers[b].before[i] != 0xa5 || buffers[b].after[i] != 0x5a) {
        printf("EVU exp2 FAIL: SEW=%u round=%u buffer=%u guard=%u\n", 8u << sew, round, b, i);
        return 1;
      }
    }
    for (unsigned i = 0; i < (VECTOR_BYTES >> sew); ++i) {
      const unsigned index = (i + round * 3) % 8;
      const uint32_t expected = sew == 1 ?
        (b == 0 ? input16[index] : result16[index]) : (b == 0 ? input32[index] : result32[index]);
      const uint32_t actual = sew == 1 ? buffers[b].data.u16[i] : buffers[b].data.u32[i];
      if (actual != expected) {
        printf("EVU exp2 FAIL: SEW=%u round=%u buffer=%u element=%u expected=0x%08x actual=0x%08x\n",
          8u << sew, round, b, i, expected, actual);
        return 1;
      }
    }
  }
  return 0;
}

#define RUN_EXP2(sew) do { \
  __asm__ volatile ("fence rw, rw" ::: "memory"); \
  XV_LOAD(1, &buffers[0].data, sew); \
  XV_EXP2(2, 1, sew); \
  XV_STORE(2, &buffers[1].data, sew); \
  XV_EXP2(1, 1, sew); \
  XV_STORE(1, &buffers[2].data, sew); \
  XV_FENCE(); \
  __asm__ volatile ("fence rw, rw" ::: "memory"); \
} while (0)

static int exp2_test_main(void) __attribute__((used, noinline));
static int exp2_test_main(void)
{
  for (unsigned sew = 1; sew <= 2; ++sew) {
    for (unsigned round = 0; round < ROUNDS; ++round) {
      initialize(sew, round);
      if (sew == 1) { RUN_EXP2(1); }
      else { RUN_EXP2(2); }
      if (check(sew, round)) return 1;
    }
    printf("EVU exp2 PASS: FP%u rounds=%u\n", 8u << sew, (unsigned)ROUNDS);
  }
  printf("EVU exp2 PASS: FP16/FP32, source preservation, in-place writes and guards\n");
  return 0;
}

int main(void) __attribute__((naked));
int main(void)
{
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail exp2_test_main\n");
}
