#include <klib.h>
#include <stdint.h>
#include "evu_csr.h"

enum { VECTOR_BYTES = 512, GROUP_BYTES = 2 * VECTOR_BYTES, RESULT_BYTES = 3 * VECTOR_BYTES, GUARD_BYTES = 64 };
#define ALIGN __attribute__((aligned(64)))
static uint8_t input[GROUP_BYTES] ALIGN;
static uint8_t sentinel[RESULT_BYTES] ALIGN;
static uint8_t expected[RESULT_BYTES] ALIGN;
static struct {
  uint8_t before[GUARD_BYTES];
  uint8_t data[RESULT_BYTES];
  uint8_t after[GUARD_BYTES];
} output ALIGN;

#define SLIDE_WIDTH(sew) do { \
  if (down) { \
    if (alias) XV_SLIDEDOWN(4, offset, 4, sew); \
    else XV_SLIDEDOWN(8, offset, 4, sew); \
  } else XV_SLIDEUP(8, offset, 4, sew); \
} while (0)

static void slide(unsigned sew, unsigned down, unsigned alias, uint64_t offset) {
  switch (sew) {
    case 0: SLIDE_WIDTH(0); break;
    case 1: SLIDE_WIDTH(1); break;
    case 2: SLIDE_WIDTH(2); break;
  }
}

static unsigned check_case(unsigned sew, unsigned lmul, unsigned down, unsigned scenario) {
  const unsigned width = 1u << sew;
  const unsigned vlmax = (VECTOR_BYTES / width) * (lmul == 1 ? 2 : 1) / (lmul == 7 ? 2 : 1);
  const uint64_t offsets[] = {0, 1, 64 / width - 1, 64 / width, 64 / width + 1,
    vlmax - 1, vlmax, vlmax + 1, UINT64_C(1) << 32, UINT64_MAX, 1, 1};
  const uint64_t offset = offsets[scenario];
  const unsigned vl = scenario == 10 ? 0 : scenario == 0 || scenario == 11 ? vlmax : vlmax - 3;
  const unsigned alias = down && scenario == 11;
  memcpy(expected, sentinel, sizeof(expected));
  if (alias) memcpy(expected, input, sizeof(input));
  for (unsigned element = 0; element < vl; ++element) {
    if (!down && element < offset) continue;
    uint8_t *result = expected + element * width;
    if (down && (offset >= vlmax || element >= vlmax - offset)) memset(result, 0, width);
    else memcpy(result, input + (down ? element + offset : element - offset) * width, width);
  }
  memset(output.before, 0x3c, GUARD_BYTES);
  memset(output.data, 0xcc, RESULT_BYTES);
  memset(output.after, 0xc3, GUARD_BYTES);
  (void)xv_setvli(VECTOR_BYTES, 0);
  XV_LOAD(4, input, 0);
  XV_LOAD(5, input + VECTOR_BYTES, 0);
  XV_LOAD(6, sentinel, 0);
  XV_LOAD(8, sentinel, 0);
  XV_LOAD(9, sentinel, 0);
  XV_LOAD(10, sentinel, 0);
  unsigned errors = xv_setvl(vl, (sew << 3) | lmul) != vl;
  XV_CSR_WRITE(XV_EFFLAGS, 21);
  XV_CSR_WRITE(XV_EVXSAT, 1);
  const uint64_t configuration = XV_CSR_READ(XV_EVCFG);
  slide(sew, down, alias, offset);
  XV_FENCE();
  errors += XV_CSR_READ(XV_EVCFG) != configuration;
  errors += XV_CSR_READ(XV_EFFLAGS) != 21 || XV_CSR_READ(XV_EVXSAT) != 1;
  errors += XV_CSR_READ(XV_EVSTATUS) != 0;
  (void)xv_setvli(VECTOR_BYTES, 0);
  if (alias) {
    XV_STORE(4, output.data, 0);
    XV_STORE(5, output.data + VECTOR_BYTES, 0);
    XV_STORE(6, output.data + 2 * VECTOR_BYTES, 0);
  } else {
    XV_STORE(8, output.data, 0);
    XV_STORE(9, output.data + VECTOR_BYTES, 0);
    XV_STORE(10, output.data + 2 * VECTOR_BYTES, 0);
  }
  XV_FENCE();
  __asm__ volatile ("fence rw, rw" ::: "memory");
  errors += memcmp(output.data, expected, RESULT_BYTES) != 0;
  for (unsigned byte = 0; byte < GUARD_BYTES; ++byte)
    errors += output.before[byte] != 0x3c || output.after[byte] != 0xc3;
  if (errors) printf("[SLIDE] FAIL sew=%u lmul=%u down=%u case=%u errors=%u\n", sew, lmul, down, scenario, errors);
  return errors;
}

static int slide_test_main(void) __attribute__((used, noinline));
static int slide_test_main(void) {
  if (XV_CSR_READ(XV_EVLENB) != VECTOR_BYTES) return 1;
  for (unsigned byte = 0; byte < GROUP_BYTES; ++byte) input[byte] = (uint8_t)(byte * 29 + byte / 251 + 7);
  memset(sentinel, 0xa5, sizeof(sentinel));
  const unsigned lmuls[] = {0, 1, 7};
  unsigned errors = 0, cases = 0;
  for (unsigned sew = 0; sew < 3; ++sew)
    for (unsigned mode = 0; mode < sizeof(lmuls) / sizeof(lmuls[0]); ++mode)
      for (unsigned down = 0; down < 2; ++down)
        for (unsigned scenario = 0; scenario < 12; ++scenario) {
          errors += check_case(sew, lmuls[mode], down, scenario);
          ++cases;
        }
  printf("[SLIDE] %s cases=%u errors=%u\n", errors ? "FAIL" : "PASS", cases, errors);
  return errors ? 1 : 0;
}

__attribute__((naked)) int main(void) {
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail slide_test_main\n");
}
