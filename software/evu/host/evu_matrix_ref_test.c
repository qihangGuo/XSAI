/* Server-side native regression of the NEMU launch API; no guest binary. */
#include <isa.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

enum { DIM = 128, DEPTH = 64, ELEMENTS = DIM * DIM };
_Static_assert(CONFIG_CUSTOM_EVU_VLEN == DIM * 32, "Use riscv64-xs-evu-ref_defconfig");
static uint64_t code = 0x80008000;
static const uint64_t a_base = 0x80010000, b_base = 0x80014000;
static const uint64_t sa_base = 0x80018000, sb_base = 0x80018400, output_base = 0x80030040;
static void (*copy_mem)(uint64_t, void *, size_t, bool);
static void (*copy_regs)(void *, bool);
static void (*copy_csrs)(void *, bool);
static void (*execute)(uint64_t);
static void (*set_async)(bool);
static int (*pending)(void);
static int (*store_event)(uint64_t, const void *, uint64_t);
static int (*matrix_event)(uint32_t, uint32_t, const void *, size_t, uint64_t, uint8_t, bool);
static int (*complete)(uint64_t, uint32_t, uint8_t, bool, uint8_t);
static int (*drain)(uint8_t, bool, uint8_t, uint64_t, uint64_t, bool);
static void (*write_evu_csr)(uint32_t, uint64_t);

#define CHECK(condition) do { if (!(condition)) { \
  fprintf(stderr, "Failed at %s:%d: %s\n", __FILE__, __LINE__, #condition); exit(1); \
} } while (0)

static uint32_t insn(unsigned opcode, unsigned f7, unsigned f3, unsigned rd, unsigned rs1, unsigned rs2) {
  return (f7 << 25) | (rs2 << 20) | (rs1 << 15) | (f3 << 12) | (rd << 7) | opcode;
}

static CPU_state run(uint32_t instruction, uint64_t arg1, uint64_t arg2) {
  // A fresh PC avoids reusing a cached decode of an overwritten instruction.
  code += 4;
  CPU_state state;
  copy_regs(&state, false);
  state.pc = code;
  state.mcause = 0;
  state.gpr[10]._64 = arg1;
  state.gpr[11]._64 = arg2;
  copy_mem(code, &instruction, 4, true);
  copy_regs(&state, true);
  execute(1);
  copy_regs(&state, false);
  return state;
}

static void good(uint32_t instruction, uint64_t arg1, uint64_t arg2) {
  CPU_state state = run(instruction, arg1, arg2);
  CHECK(state.mcause == 0 && state.pc == code + 4);
}

static uint32_t raw(float value) { uint32_t bits; memcpy(&bits, &value, 4); return bits; }

int main(int argc, char **argv) {
  CHECK(argc == 2);
  void *library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
  if (!library) { fprintf(stderr, "%s\n", dlerror()); return 1; }
#define SYMBOL(target, name) do { *(void **)(&(target)) = dlsym(library, name); CHECK(target); } while (0)
  void (*init)(void);
  void (*ram_size)(size_t);
  SYMBOL(init, "difftest_init");
  SYMBOL(ram_size, "difftest_set_ramsize");
  SYMBOL(copy_mem, "difftest_memcpy");
  SYMBOL(copy_regs, "difftest_regcpy");
  SYMBOL(copy_csrs, "difftest_csrcpy");
  SYMBOL(execute, "difftest_exec");
  SYMBOL(set_async, "difftest_evu_set_async");
  SYMBOL(pending, "difftest_evu_pending");
  SYMBOL(store_event, "difftest_evu_store");
  SYMBOL(matrix_event, "difftest_evu_matrix");
  SYMBOL(complete, "difftest_evu_complete");
  SYMBOL(drain, "difftest_evu_drain");
  SYMBOL(write_evu_csr, "evu_csr_write");
  ram_size(64 * 1024 * 1024);
  init();
  CPU_state state;
  copy_regs(&state, false);
  state.mode = 3;
  state.satp = 0;
  state.mstatus = (UINT64_C(1) << 13) | (UINT64_C(1) << 9);
  state.mtvec = 0x8000f000;
  state.medeleg = state.mideleg = state.mie = 0;
  copy_regs(&state, true);
  uint64_t csrs[4096];
  copy_csrs(csrs, false);
  csrs[0xbc8] = 9; // ES Initial, supervisor access
  csrs[0x30c] |= 1; // mstateen0.C
  csrs[0x744] |= UINT64_C(1) << 3;
  copy_csrs(csrs, true);
  good(insn(0x2b, 0x21, 0, 0, 10, 0), 2, 0);
  good(insn(0x2b, 0x21, 0, 1, 10, 0), 2, 0);
  good(insn(0x2b, 0x21, 0, 4, 10, 0), 4, 0);
  good(insn(0x2b, 0x11, 0, 0, 10, 0), DIM, 0);
  good(insn(0x2b, 0x09, 0, 0, 10, 0), DEPTH, 0);
  good(insn(0x2b, 0x19, 0, 0, 10, 0), DIM, 0);

  static int8_t a[DIM * DEPTH], b[DIM * DEPTH];
  static float scale_a[DIM], scale_b[DIM];
  static uint32_t expected[ELEMENTS], observed[ELEMENTS];
  static uint8_t expected_flags[ELEMENTS];
  static uint8_t guards[ELEMENTS * 4 + 128];
  memset(guards, 0xa5, sizeof(guards));
  CHECK(matrix_event(0, 0, expected, 32, 255, 0, false) != 0); // No launch.
  unsigned beats = 0, stores = 0;
  for (unsigned varied = 0; varied < 3; ++varied) {
    for (unsigned r = 0; r < DIM; ++r) {
      scale_a[r] = varied == 2 ? 1.1f : varied ? 0.5f + (r % 7) * 0.25f : 1.0f;
      scale_b[r] = varied == 2 ? 1.3f : varied ? 0.25f + (r % 11) * 0.125f : 1.0f;
      for (unsigned k = 0; k < DEPTH; ++k) {
        a[r * DEPTH + k] = (int)(r % 5) - 2;
        b[r * DEPTH + k] = (int)(r % 3) - 1;
      }
    }
    for (unsigned n = 0; n < DIM; ++n)
      for (unsigned m = 0; m < DIM; ++m) {
        int32_t total = 0;
        for (unsigned k = 0; k < DEPTH; ++k) total += b[n * DEPTH + k] * a[m * DEPTH + k];
        const float first = (float)total * scale_a[m];
        const float final = first * scale_b[n];
        expected[n * DIM + m] = raw(final);
        // Products of two FP32 significands are exact in IEEE binary64.
        expected_flags[n * DIM + m] =
          ((double)first != (double)total * scale_a[m] ||
           (double)final != (double)first * scale_b[n]) ? 1 : 0;
      }
    copy_mem(a_base, a, sizeof(a), true);
    copy_mem(b_base, b, sizeof(b), true);
    copy_mem(sa_base, scale_a, sizeof(scale_a), true);
    copy_mem(sb_base, scale_b, sizeof(scale_b), true);
    copy_mem(output_base - 64, guards, sizeof(guards), true);
    set_async(true);
    const uint32_t load_a = insn(0x0b, 0x10, 2, 1, 10, 0);
    const uint32_t load_b = insn(0x0b, 0x10, 2, 2, 10, 0);
    good(load_a, sa_base, 0);
    CHECK(complete(code, load_a, 0, false, 0) == 0);
    good(load_b, sb_base, 0);
    CHECK(complete(code, load_b, 0, false, 0) == 0);
    const uint32_t launch = insn(0x0b, 0x18, 4, 2, 10, 1);
    good(launch, output_base, 0);
    CHECK(pending() == 2);
    CHECK(complete(code, launch, 0, false, 0) == 0 && pending() == 1);
    CHECK(matrix_event(0, 0, expected, 32, 255, 0, false) != 0); // MMA not issued.
    good(insn(0x2b, 0, 3, 4, 0, 0), 0, 0);
    good(insn(0x2b, 0, 1, 0, 10, 11), b_base, DEPTH);
    good(insn(0x2b, 8, 1, 1, 10, 11), a_base, DEPTH);
    good(insn(0x2b, 0, 2, 4, 0, 1), 0, 0);
    copy_mem(output_base, observed, sizeof(observed), false);
    CHECK(!memcmp(observed, guards, sizeof(observed)));
    const uint32_t wait = insn(0x0b, 0x19, 0, 0, 0, 0);
    good(wait, 0, 0);
    CHECK(complete(code, wait, 0, false, 0) == 0 && pending() == 1);
    const uint32_t fence = insn(0x0b, 0x1a, 0, 0, 0, 0);
    if (varied != 2) {
      good(fence, 0, 0);
      CHECK(complete(code, fence, 0, false, 0) != 0); // No output has been observed.
    }
    uint8_t sticky_flags = 0;
    for (unsigned nb = 0; nb < DIM / 8; ++nb) {
      for (unsigned mb = 0; mb < DIM / 8; ++mb) {
        for (unsigned r = 0; r < 8; ++r) {
          const unsigned n = nb * 8 + r, m = mb * 8;
          const bool last = nb == DIM / 8 - 1 && mb == DIM / 8 - 1 && r == 7;
          uint32_t *result = &expected[n * DIM + m];
          uint8_t beat_flags = 0;
          for (unsigned lane = 0; lane < 8; ++lane) beat_flags |= expected_flags[n * DIM + m + lane];
          if (!beats) {
            uint32_t wrong[8];
            memcpy(wrong, result, sizeof(wrong));
            wrong[0] ^= 1;
            CHECK(matrix_event(n, m, NULL, 32, 255, 0, false) != 0);
            CHECK(matrix_event(n, m, result, 0, 255, 0, false) != 0);
            CHECK(matrix_event(n, m, result, 3, 255, 0, false) != 0);
            CHECK(matrix_event(n, m, result, 68, 255, 0, false) != 0);
            CHECK(matrix_event(DIM, m, result, 32, 255, 0, false) != 0);
            CHECK(matrix_event(n, DIM - 1, result, 32, 255, 0, false) != 0);
            CHECK(matrix_event(n, m + 1, result, 32, 255, 0, false) != 0);
            CHECK(matrix_event(n, m, result, 12, 7, 0, false) != 0);
            CHECK(matrix_event(n, m, result, 32, 15, 0, false) != 0);
            CHECK(matrix_event(n, m, wrong, 32, 255, 0, false) != 0);
            CHECK(matrix_event(n, m, result, 32, 255, 1, false) != 0);
            CHECK(matrix_event(n, m, result, 32, 255, 0, true) != 0);
          }
          CHECK(matrix_event(n, m, result, 32, 255, beat_flags, last) == 0);
          // Check duplicate rejection at stream boundaries without logging every beat.
          if (!beats || last) CHECK(matrix_event(n, m, result, 32, 255, beat_flags, last) != 0);
          sticky_flags |= beat_flags;
          if (varied == 2 && nb == 0 && mb == 0 && r == 0) {
            CHECK(sticky_flags == 1);
            good(wait, 0, 0);
            CHECK(complete(code, wait, 0, false, 0) == 0); // WAIT does not publish matrix NX.
            CHECK(drain(sticky_flags, false, 0, 0, 0, false) != 0);
          }
          uint64_t address = output_base + (n * DIM + m) * 4;
          uint64_t line = address & ~UINT64_C(63);
          uint8_t data[64];
          copy_mem(line, data, sizeof(data), false);
          memcpy(data + (address & 63), result, 32);
          if (!stores) {
            data[63] ^= 1; // Preserve the next half-line until its own beat.
            CHECK(store_event(line, data, UINT64_MAX) != 0);
            data[63] ^= 1;
            CHECK(store_event(line, data, 0xffffffff) != 0);
          }
          if (last) { // Last write Ack is still missing.
            if (varied == 2) CHECK(drain(sticky_flags, false, 0, 0, 0, false) != 0);
            else CHECK(complete(code, fence, sticky_flags, false, 0) != 0);
          }
          CHECK(store_event(line, data, UINT64_MAX) == 0);
          ++beats;
          ++stores;
        }
      }
    }
    if (varied == 2) {
      CHECK(drain(sticky_flags ^ 1, false, 0, 0, 0, false) != 0);
      CHECK(drain(sticky_flags, false, 0, 0, 0, false) == 0 && pending() == 0);
    } else CHECK(complete(code, fence, sticky_flags, false, 0) == 0 && pending() == 0);
    copy_csrs(csrs, false);
    CHECK((csrs[0x8c1] & 31) == sticky_flags && (csrs[1] & 31) == 0);
    write_evu_csr(0x8c1, 0);
    CHECK(drain(0, false, 0, 0, 0, false) == 0);
    copy_csrs(csrs, false);
    CHECK(csrs[0x8c1] == 0); // A drained flag must not reappear after software clears it.
    set_async(false);
    copy_mem(output_base, observed, sizeof(observed), false);
    CHECK(!memcmp(observed, expected, sizeof(observed)));
    uint8_t guard[64];
    copy_mem(output_base - 64, guard, 64, false);
    CHECK(!memcmp(guard, guards, 64));
    copy_mem(output_base + sizeof(expected), guard, 64, false);
    CHECK(!memcmp(guard, guards, 64));
  }
  CHECK(run(insn(0x0b, 0x18, 0, 2, 10, 1), output_base, 0).mcause == 2);
  CHECK(pending() == 0);
  printf("EVU matrix: 3 launches, %u result beats, %u RMW stores, state/metadata, NX publication, wrong results/flags/guards, duplicate beats and premature fences checked\n",
         beats, stores);
  dlclose(library);
  return 0;
}
