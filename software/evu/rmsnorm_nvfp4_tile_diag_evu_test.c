/* One layout for all four experiments. Select with AM mainargs=L/R/B/Q. */
static int diag_block4;
#define EVU_RMS_TILE_DIAGNOSTIC 1
#include "rmsnorm_nvfp4_tile128x64_perf_evu_test.c"

#define FIRST_EIGHT(op) op(0); op(1); op(2); op(3); op(4); op(5); op(6); op(7)
#define LAST_EIGHT(op) op(8); op(9); op(10); op(11); op(12); op(13); op(14); op(15)
#define LOAD_ROW(r) XV_LOAD(r, &inputs[(16 * tile + (r)) * COLS], 2)

static void load_rows(unsigned tile) {
  (void)xv_setvli(COLS, 0x10);
  FIRST_EIGHT(LOAD_ROW);
  (void)xv_setvli(16, 0x10);
  (void)xv_setvli(COLS, 0x10);
  LAST_EIGHT(LOAD_ROW);
  (void)xv_setvli(16, 0x10);
}

/* Re-expand the real sum/collect schedule with only its row loads removed. */
#undef PERF_ROW_LOAD
#define PERF_ROW_LOAD(tile, row) ((void)0)
static void resident_sums(void) {
  (void)xv_setvli(COLS, 0x10);
  PERF_SUM_EIGHT(0, 0, 1, 2, 3, 4, 5, 6, 7);
  (void)xv_setvli(COLS, 0x10);
  PERF_SUM_EIGHT(0, 8, 9, 10, 11, 12, 13, 14, 15);
}

static unsigned row_probe(int resident) {
  if (resident) {
    load_rows(0);
    XV_LOAD_E0(RN_QUANT_ZERO, &constants[0]);
  }
  synchronize();
  XV_CSR_WRITE(XV_EFFLAGS, 0);
  const uint64_t start = read_cycle();
  if (resident) resident_sums(); else load_rows(0);
  synchronize();
  const uint64_t end = read_cycle();
  const unsigned flags = XV_CSR_READ(XV_EFFLAGS);
  const unsigned errors = end <= start || (XV_CSR_READ(XV_EVSTATUS) & 2) || (flags & 0x18);
  printf("[RN-ROW] group=0 cycles=%lu flags=%u errors=%u\n",
    (unsigned long)(end - start), flags, errors);
  return errors;
}

/* Both output buffers are aligned and multiples of eight bytes. No scalar reference model. */
static uint64_t output_signature(const uint8_t *buf, unsigned bytes) {
  uint64_t h = 1;
  for (unsigned i = 0; i < bytes; i += sizeof(uint64_t)) {
    uint64_t word;
    __builtin_memcpy(&word, __builtin_assume_aligned(buf + i, 8), sizeof(word));
    h = (h ^ word) * UINT64_C(1099511628211);
  }
  return h;
}

__attribute__((used, noinline)) static int diag_main(const char *args) {
  const char mode = args[0] ? args[0] : 'B';
  if ((args[0] && args[1]) || (mode != 'L' && mode != 'R' && mode != 'B' && mode != 'Q')) return 1;
  __asm__ volatile ("csrw frm, x0" ::: "memory");
  const uint64_t required = XV_CAP_RVV_MATH | XV_CAP_LOW_PRECISION | XV_CAP_GATHER_IMM | XV_CAP_BLOCK_QUANT;
  const uint64_t cap = XV_CSR_READ(XV_EVCAP);
  if (XV_CSR_READ(XV_EVLENB) != 512 || (cap & 255) != 2 || (cap & required) != required ||
      xv_setvli(COLS, 0x10) != COLS || xv_setvli(16, 0x10) != 16) return 1;
  diag_block4 = mode == 'Q';
  const int row_mode = mode == 'L' || mode == 'R';
  initialize_inputs(row_mode ? 16 : ROWS);
  unsigned errors = 0, flags = 0;
  uint64_t cycles = 0;
  if (row_mode) {
    errors = row_probe(mode == 'R');
    flags = XV_CSR_READ(XV_EFFLAGS);
  } else {
    prepare();
    const uint64_t start = read_cycle();
    execute_perf_tile();
    synchronize();
    const uint64_t end = read_cycle();
    cycles = end - start;
    flags = XV_CSR_READ(XV_EFFLAGS);
    errors = end <= start || (XV_CSR_READ(XV_EVSTATUS) & 2) || (flags & 0x18);
    printf("[RN-OUTPUT] scales=%lx packed=%lx\n",
      (unsigned long)output_signature(scales.data, sizeof(scales.data)),
      (unsigned long)output_signature(packed.data, sizeof(packed.data)));
  }
  printf("[RN-DIAG] mode=%c inputs=%lx cycles=%lu flags=%u errors=%u\n",
    mode, (unsigned long)(uintptr_t)inputs, (unsigned long)cycles, flags, errors);
  return errors != 0;
}

/* AM passes mainargs in a0; use its link name without Clang's hosted main rules. */
int diag_entry(const char *args) __asm__("main");
__attribute__((naked)) int diag_entry(const char *args) {
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail diag_main\n");
}
