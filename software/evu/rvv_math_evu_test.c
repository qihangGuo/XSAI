#include <klib.h>
#include <stdint.h>
#include "evu_csr.h"

enum { VECTOR_BYTES = 512, GROUP_BYTES = 4 * VECTOR_BYTES, GUARD_BYTES = 64 };
#define ALIGN __attribute__((aligned(64)))
static uint8_t input[GROUP_BYTES] ALIGN;
static uint8_t other[2 * VECTOR_BYTES] ALIGN;
static uint8_t expected[GROUP_BYTES] ALIGN;
static uint8_t sentinel[GROUP_BYTES] ALIGN;
static struct {
  uint8_t before[GUARD_BYTES];
  uint8_t data[GROUP_BYTES];
  uint8_t after[GUARD_BYTES];
} output ALIGN;

static uint32_t float_bits(float value) {
  union { float fp; uint32_t bits; } converted = { .fp = value };
  return converted.bits;
}

static void synchronize(void) {
  XV_FENCE();
  __asm__ volatile ("fence rw, rw" ::: "memory");
}

static void execute(unsigned operation) {
  switch (operation) {
    case 0x60: XV_FSQRT(8, 4, 2); break;
    case 0x61: XV_FRSQRT7(8, 4, 2); break;
    case 0x62: XV_FSQRT_E0(8, 4); break;
    case 0x63: XV_FRSQRT7_E0(8, 4); break;
    case 0x64: XV_FREC7(8, 4, 2); break;
    case 0x65: XV_FREC7_E0(8, 4); break;
    case 0x66: XV_FSGNJX(8, 12, 4, 2); break;
    case 0x67: XV_FWCVT_F_F(8, 4); break;
    case 0x68: XV_FNCVT_F_F(8, 4); break;
    case 0x69: XV_FCVT_X_F(8, 4); break;
    case 0x6a: XV_FCVT_F_X(8, 4); break;
    case 0x6b: XV_NCLIP_IMM(8, 4, 0, 1); break;
    case 0x6c: XV_ZEXT2(8, 4, 2); break;
    case 0x6d: XV_SEXT2(8, 4, 2); break;
    case 0x6e: XV_NSRL_IMM(8, 4, 16, 1); break;
  }
}

static unsigned run_case(unsigned operation, unsigned mode) {
  const unsigned sew = operation == 0x67 || operation == 0x68 || operation == 0x6b || operation == 0x6e ? 1 : 2;
  const unsigned input_width = operation == 0x67 || operation == 0x6c || operation == 0x6d ? 2 : 4;
  const unsigned output_width = operation == 0x68 || operation == 0x6b || operation == 0x6e ? 2 : 4;
  const unsigned lmul = mode == 2 ? 7 : mode == 3 ? 1 : 0;
  const unsigned vl = mode == 1 ? 0 : mode == 3 ? 200 : 13;
  const unsigned e0 = operation == 0x62 || operation == 0x63 || operation == 0x65;
  const unsigned active = e0 && vl ? 1 : vl;
  memset(input, 0, sizeof(input));
  memset(other, 0, sizeof(other));
  memset(sentinel, 0xa5, sizeof(sentinel));
  memcpy(expected, sentinel, sizeof(expected));
  memset(output.before, 0xa5, GUARD_BYTES);
  memset(output.after, 0x5a, GUARD_BYTES);
  unsigned expected_flags = 0, expected_sat = 0;
  for (unsigned element = 0; element < sizeof(input) / input_width; ++element) {
    uint32_t value = float_bits(4.0f), result = 0, sign = UINT32_C(0x80000000);
    switch (operation) {
      case 0x60: case 0x62: result = float_bits(2.0f); break;
      case 0x61: case 0x63: result = UINT32_C(0x3eff0000); break;
      case 0x64: case 0x65: result = UINT32_C(0x3e7f0000); break;
      case 0x66: value = UINT32_C(0xff800001); result = UINT32_C(0x7f800001); break;
      case 0x67: value = element & 1 ? 0xc000 : 0x3c00; result = float_bits(element & 1 ? -2.0f : 1.0f); break;
      case 0x68: value = float_bits(element & 1 ? -2.0f : 1.0f); result = element & 1 ? 0xc000 : 0x3c00; break;
      case 0x69: value = float_bits(element & 1 ? -3.5f : 2.5f); result = element & 1 ? (uint32_t)-4 : 2; break;
      case 0x6a: value = (uint32_t)((int)element - 100); result = float_bits((float)((int)element - 100)); break;
      case 0x6b: value = element & 1 ? (uint32_t)-100000 : 100000; result = element & 1 ? 0x8000 : 0x7fff; break;
      case 0x6c: value = 0xf123; result = 0xf123; break;
      case 0x6d: value = 0xf123; result = UINT32_C(0xfffff123); break;
      case 0x6e: value = UINT32_C(0x1234abcd) + element; result = value >> 16; break;
    }
    memcpy(input + input_width * element, &value, input_width);
    if (4 * element < sizeof(other)) memcpy(other + 4 * element, &sign, 4);
    if (element < active) {
      memcpy(expected + output_width * element, &result, output_width);
      if (operation == 0x69) expected_flags = 1;
      if (operation == 0x6b) expected_sat = 1;
    }
  }
  __asm__ volatile ("fence rw, rw" ::: "memory");
  (void)xv_setvli(VECTOR_BYTES, 0);
  XV_LOAD(4, input, 0); XV_LOAD(5, input + VECTOR_BYTES, 0);
  XV_LOAD(6, input + 2 * VECTOR_BYTES, 0); XV_LOAD(7, input + 3 * VECTOR_BYTES, 0);
  XV_LOAD(8, sentinel, 0); XV_LOAD(9, sentinel + VECTOR_BYTES, 0);
  XV_LOAD(10, sentinel + 2 * VECTOR_BYTES, 0); XV_LOAD(11, sentinel + 3 * VECTOR_BYTES, 0);
  XV_LOAD(12, other, 0); XV_LOAD(13, other + VECTOR_BYTES, 0);
  synchronize();
  XV_CSR_WRITE(XV_EFFLAGS, 0);
  XV_CSR_WRITE(XV_EVXSAT, 0);
  if (xv_setvl(vl, (sew << 3) | lmul) != vl) return 1;
  execute(operation);
  synchronize();
  unsigned errors = XV_CSR_READ(XV_EFFLAGS) != expected_flags ||
    XV_CSR_READ(XV_EVXSAT) != expected_sat || (XV_CSR_READ(XV_EVSTATUS) & 2);
  const uint64_t config = XV_CSR_READ(XV_EVCFG);
  errors += (uint32_t)config != vl || ((config >> 32) & 255) != ((sew << 3) | lmul);
  (void)xv_setvli(VECTOR_BYTES, 0);
  XV_STORE(8, output.data, 0); XV_STORE(9, output.data + VECTOR_BYTES, 0);
  XV_STORE(10, output.data + 2 * VECTOR_BYTES, 0); XV_STORE(11, output.data + 3 * VECTOR_BYTES, 0);
  synchronize();
  errors += memcmp(output.data, expected, sizeof(expected)) != 0;
  for (unsigned index = 0; index < GUARD_BYTES; ++index)
    errors += output.before[index] != 0xa5 || output.after[index] != 0x5a;
  if (errors) printf("[RVVMATH] FAIL op=%02x mode=%u errors=%u\n", operation, mode, errors);
  return errors;
}

__attribute__((used, noinline)) static int rvv_math_test_main(void) {
  if (xv_init(VECTOR_BYTES, XV_CAP_RETURNED_VL | XV_CAP_RVV_MATH)) {
    printf("[RVVMATH] FAIL requires EVLEN=4096 and EVCAP.RVV_MATH\n");
    return 1;
  }
  unsigned cases = 0;
  for (unsigned operation = 0x60; operation <= 0x6e; ++operation) {
    const unsigned scalar = operation == 0x62 || operation == 0x63 || operation == 0x65;
    for (unsigned mode = 0; mode < (scalar ? 2u : 4u); ++mode) {
      if (run_case(operation, mode)) return 1;
      ++cases;
    }
  }
  printf("[RVVMATH] PASS cases=%u instructions=15 tails=1 guards=1 flags=1 lmul=m1,m2,mf2\n", cases);
  return 0;
}

__attribute__((naked)) int main(void) {
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail rvv_math_test_main\n");
}
