#include <am.h>
#include <klib.h>
#include <stdint.h>
#include "evu_intrinsics.h"

/* DefaultExternalVectorMatrixConfig: VLEN=4096. No AME/CUTE instructions.
 * Use the updated CONFIG_CUSTOM_EVU NEMU reference for architectural Difftest.
 */
enum { VECTOR_BYTES = 512, GUARD_BYTES = 64, ROUNDS = 3, OUTPUTS = 4 };

union VectorData {
  uint8_t u8[VECTOR_BYTES];
  uint16_t u16[VECTOR_BYTES / 2];
  uint32_t u32[VECTOR_BYTES / 4];
};

struct GuardedVector {
  uint8_t before[GUARD_BYTES];
  union VectorData data;
  uint8_t after[GUARD_BYTES];
} __attribute__((aligned(64)));

/* Volatile keeps initialization and the golden-result checker on the scalar
 * core, rather than letting the compiler replace them with RVV operations.
 */
static volatile struct GuardedVector inputs[2];
static volatile struct GuardedVector outputs[OUTPUTS];

static uint32_t element(const volatile union VectorData *data, unsigned sew, unsigned index)
{
  if (sew == 0) return data->u8[index];
  if (sew == 1) return data->u16[index];
  return data->u32[index];
}

static void initialize(unsigned round)
{
  for (unsigned v = 0; v < 2 + OUTPUTS; ++v) {
    volatile struct GuardedVector *buffer = v < 2 ? &inputs[v] : &outputs[v - 2];
    for (unsigned i = 0; i < GUARD_BYTES; ++i) {
      buffer->before[i] = 0xa5;
      buffer->after[i] = 0x5a;
    }
    for (unsigned i = 0; i < VECTOR_BYTES; ++i) {
      buffer->data.u8[i] = v < 2 ?
        (uint8_t)((i * (v ? 73u : 37u) + round * 97u + v * 19u) ^ (i >> 2)) : 0xcc;
    }
  }
}

static int check_guards(unsigned sew, unsigned round)
{
  for (unsigned v = 0; v < 2 + OUTPUTS; ++v) {
    const volatile struct GuardedVector *buffer = v < 2 ? &inputs[v] : &outputs[v - 2];
    for (unsigned i = 0; i < GUARD_BYTES; ++i) {
      if (buffer->before[i] != 0xa5 || buffer->after[i] != 0x5a) {
        printf("EVU add FAIL: SEW=%u round=%u buffer=%u guard=%u\n", 8u << sew, round, v, i);
        return 1;
      }
    }
  }
  return 0;
}

static int check_results(unsigned sew, unsigned round)
{
  const unsigned count = VECTOR_BYTES >> sew;
  const uint32_t mask = UINT32_MAX >> (32 - (8u << sew));
  for (unsigned i = 0; i < count; ++i) {
    const uint32_t a = element(&inputs[0].data, sew, i);
    const uint32_t b = element(&inputs[1].data, sew, i);
    const uint32_t expected[OUTPUTS] = {a, (a + b) & mask, (a + b) & mask, (a + b + b) & mask};
    for (unsigned v = 0; v < OUTPUTS; ++v) {
      const uint32_t actual = element(&outputs[v].data, sew, i);
      if (actual != expected[v]) {
        printf("EVU add FAIL: SEW=%u round=%u output=%u element=%u expected=0x%08x actual=0x%08x\n",
          8u << sew, round, v, i, expected[v], actual);
        return 1;
      }
    }
  }
  return check_guards(sew, round);
}

/* Each SEW is an assembly immediate. The stores also check RAW dependencies
 * and both destination/source aliases without intermediate software waits.
 */
#define RUN_ADD(sew) do { \
  __asm__ volatile ("fence rw, rw" ::: "memory"); \
  XV_LOAD(1, &inputs[0].data, sew); \
  XV_LOAD(17, &inputs[1].data, sew); \
  XV_STORE(1, &outputs[0].data, sew); \
  XV_BINARY(1, 31, 1, 17, sew); \
  XV_STORE(31, &outputs[1].data, sew); \
  XV_BINARY(1, 1, 1, 17, sew); \
  XV_STORE(1, &outputs[2].data, sew); \
  XV_BINARY(1, 17, 31, 17, sew); \
  XV_STORE(17, &outputs[3].data, sew); \
  XV_FENCE(); \
  __asm__ volatile ("fence rw, rw" ::: "memory"); \
} while (0)

static int vector_add_test_main(void) __attribute__((used, noinline));
static int vector_add_test_main(void)
{
  for (unsigned sew = 0; sew < 3; ++sew) {
    for (unsigned round = 0; round < ROUNDS; ++round) {
      initialize(round);
      if (sew == 0) { RUN_ADD(0); }
      else if (sew == 1) { RUN_ADD(1); }
      else { RUN_ADD(2); }
      if (check_results(sew, round)) return 1;
    }
    printf("EVU add PASS: SEW=%u rounds=%u\n", 8u << sew, (unsigned)ROUNDS);
  }
  printf("EVU vector add PASS: copy, add, aliases and guards; no matrix operations\n");
  return 0;
}

int main(void) __attribute__((naked));
int main(void)
{
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail vector_add_test_main\n");
}
