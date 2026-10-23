/* Native host regression against the built NEMU shared library; no guest binary. */
#include <isa.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

enum { EVU_ELEMENTS = CONFIG_CUSTOM_EVU_VLEN / 32 };
_Static_assert(CONFIG_CUSTOM_EVU_VLEN == 4096, "Use the EVU reference configuration");

static void (*copy_mem)(uint64_t, void *, size_t, bool);
static void (*copy_regs)(void *, bool);
static void (*copy_csrs)(void *, bool);
static void (*execute)(uint64_t);
static void (*set_async)(bool);
static int (*pending)(void);
static int (*get_reg)(uint32_t, void *, size_t);
static int (*store_event)(uint64_t, const void *, uint64_t);
static int (*complete)(uint64_t, uint32_t, uint8_t, bool, uint8_t);
static CPU_state initial;
static const uint64_t data_va = UINT64_C(0x40000ffc);
static const uint64_t code_va = UINT64_C(0x40008000);
static uint64_t data_pages[2] = {0x80010000, 0x80030000};
static uint64_t csrs[4096];

#define CHECK(condition) do { if (!(condition)) { \
  fprintf(stderr, "Failed at %s:%d: %s\n", __FILE__, __LINE__, #condition); exit(1); \
} } while (0)

static uint64_t physical(uint64_t va) {
  return data_pages[(va >> 12) - (data_va >> 12)] | (va & 4095);
}

static void map_page(unsigned index, uint64_t pa, uint64_t flags) {
  uint64_t entry = ((pa >> 12) << 10) | flags;
  copy_mem(0x80003000 + index * 8, &entry, 8, true);
}

static uint32_t instruction(unsigned opcode) {
  return (opcode << 25) | (1 << 15) | (2 << 12) | (3 << 7) | 0x0b;
}

static CPU_state run(unsigned opcode, uint64_t base) {
  CPU_state state = initial;
  const uint32_t program[] = {0x12000073, instruction(opcode), 0x00000013};
  copy_mem(0x80008000, (void *)program, sizeof(program), true);
  state.pc = code_va;
  state.gpr[1]._64 = base;
  copy_regs(&state, true);
  execute(1); // SFENCE.VMA makes page-table edits visible before each case.
  execute(1);
  copy_regs(&state, false);
  return state;
}

static void put_input(const uint32_t *input) {
  uint8_t guards[4096];
  memset(guards, 0xa5, sizeof(guards));
  for (unsigned page = 0; page < 2; ++page)
    copy_mem(data_pages[page], guards, sizeof(guards), true);
  for (unsigned i = 0; i < EVU_ELEMENTS; ++i)
    copy_mem(physical(data_va + i * 4), (void *)&input[i], 4, true);
}

static void get_output(uint32_t *output) {
  for (unsigned i = 0; i < EVU_ELEMENTS; ++i)
    copy_mem(physical(data_va + i * 4), &output[i], 4, false);
}

static void check_guards(void) {
  uint8_t page[4096];
  for (unsigned index = 0; index < 2; ++index) {
    copy_mem(data_pages[index], page, sizeof(page), false);
    for (unsigned byte = 0; byte < sizeof(page); ++byte) {
      const uint64_t va = (data_va & ~UINT64_C(4095)) + index * 4096 + byte;
      if (va < data_va || va >= data_va + EVU_ELEMENTS * 4) CHECK(page[byte] == 0xa5);
    }
  }
}

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
  SYMBOL(get_reg, "difftest_evu_get_reg");
  SYMBOL(store_event, "difftest_evu_store");
  SYMBOL(complete, "difftest_evu_complete");
  ram_size(64 * 1024 * 1024);
  init();
  copy_regs(&initial, false);
  initial.mode = 1; // Supervisor mode, Sv39, no delegation of faults.
  initial.mstatus = (UINT64_C(3) << 13) | (UINT64_C(3) << 9);
  initial.satp = (UINT64_C(8) << 60) | (UINT64_C(0x80001000) >> 12);
  initial.mtvec = 0x8000f000;
  initial.medeleg = initial.mideleg = initial.mie = 0;
  initial.mcause = initial.scause = 0;
  const uint32_t nop = 0x00000013;
  copy_mem(initial.mtvec, (void *)&nop, 4, true);
  uint64_t table[512] = {0};
  copy_mem(0x80001000, table, sizeof(table), true);
  copy_mem(0x80002000, table, sizeof(table), true);
  copy_mem(0x80003000, table, sizeof(table), true);
  uint64_t pointer = ((UINT64_C(0x80002000) >> 12) << 10) | 1;
  copy_mem(0x80001008, &pointer, 8, true);
  pointer = ((UINT64_C(0x80003000) >> 12) << 10) | 1;
  copy_mem(0x80002000, &pointer, 8, true);
  map_page(0, data_pages[0], 0xc7); // V/R/W/A/D
  map_page(1, data_pages[1], 0xc7);
  map_page(8, 0x80008000, 0xcb); // V/R/X/A/D
  copy_csrs(csrs, false);
  csrs[0x3a0] = 0x0f; // PMP TOR, read/write/execute.
  csrs[0x3b0] = UINT64_C(0x84000000) >> 2;
  csrs[0xbc8] = 9; // ES Initial, supervisor access
  csrs[0x30c] |= 1; // mstateen0.C
  csrs[0x744] |= UINT64_C(1) << 3; // Permit normal traps with Smrnmi enabled.
  copy_csrs(csrs, true);

  unsigned stores = 0;
  uint32_t pattern[EVU_ELEMENTS], result[EVU_ELEMENTS];
  for (unsigned i = 0; i < EVU_ELEMENTS; ++i) pattern[i] = 0x12340000 + i;
  put_input(pattern);
  CHECK(run(0x10, data_va).mcause == 0);
  CHECK(get_reg(3, result, sizeof(result)) == 0 && !memcmp(pattern, result, sizeof(result)));
  // Remap the second page, then store the previously loaded register.
  data_pages[1] = 0x80050000;
  map_page(1, data_pages[1], 0xc7);
  uint32_t zeros[EVU_ELEMENTS] = {0};
  put_input(zeros);
  CHECK(run(0x11, data_va).mcause == 0);
  get_output(result);
  CHECK(!memcmp(pattern, result, sizeof(result)));
  check_guards();

  // Asynchronous stores publish only physical events, preserving RMW guards.
  put_input(zeros);
  set_async(true);
  CHECK(run(0x11, data_va).mcause == 0 && pending() == 1);
  get_output(result);
  CHECK(!memcmp(zeros, result, sizeof(result)));
  for (uint64_t line = data_va & ~UINT64_C(63); line <= ((data_va + sizeof(pattern) - 1) & ~UINT64_C(63)); line += 64) {
    const uint64_t pa = physical(line);
    uint8_t data[64];
    copy_mem(pa, data, sizeof(data), false);
    for (unsigned byte = 0; byte < 64; ++byte)
      if (line + byte >= data_va && line + byte < data_va + sizeof(pattern))
        data[byte] = ((uint8_t *)pattern)[line + byte - data_va];
    if (line == (data_va & ~UINT64_C(63))) {
      data[0] ^= 1; // RMW guard corruption must be rejected.
      CHECK(store_event(pa, data, UINT64_MAX) != 0);
      data[0] ^= 1;
    }
    CHECK(store_event(pa, data, UINT64_MAX) == 0);
    ++stores;
  }
  CHECK(complete(code_va + 4, instruction(0x11), 0, false, 0) == 0);
  CHECK(pending() == 0);
  set_async(false);
  get_output(result);
  CHECK(!memcmp(pattern, result, sizeof(result)));
  check_guards();

  // The removed operation must trap before touching memory or queuing work.
  set_async(true);
  CHECK(run(0x38, data_va).mcause == 2 && pending() == 0);
  get_output(result);
  CHECK(!memcmp(pattern, result, sizeof(result)));
  CHECK(get_reg(3, result, sizeof(result)) == 0 && !memcmp(pattern, result, sizeof(result)));
  check_guards();
  set_async(false);

  // A later-page failure must leave both the memory and the VRF unchanged.
  put_input(zeros);
  map_page(1, data_pages[1], 0);
  set_async(true);
  CPU_state fault = run(0x10, data_va);
  CHECK(fault.mcause == 13 && fault.mtval == 0x40001000 && pending() == 0);
  CHECK(get_reg(3, result, sizeof(result)) == 0 && !memcmp(pattern, result, sizeof(result)));
  map_page(1, data_pages[1], 0x43); // Readable, not writable.
  fault = run(0x11, data_va);
  CHECK(fault.mcause == 15 && fault.mtval == 0x40001000 && pending() == 0);
  get_output(result);
  CHECK(!memcmp(result, zeros, sizeof(result)));
  set_async(false);
  map_page(1, data_pages[1], 0xc7);
  CHECK(run(0x11, data_va).mcause == 0); // Retry the complete instruction.
  get_output(result);
  CHECK(!memcmp(pattern, result, sizeof(result)));
  check_guards();
  printf("EVU MMU: %u physical stores, cross-page VRF transfer, remap, precise faults, retry and retired opcode rejection passed\n",
    stores);
  dlclose(library);
  return 0;
}
