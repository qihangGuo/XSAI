#include <am.h>
#include <klib.h>
#include <stdint.h>
#include "evu_intrinsics.h"

/* Build on the server with NAME=virtual_memory_evu_test. All guest addresses
 * below are virtual; consecutive pages deliberately map across a guard page.
 */
#define INPUT_VA UINT64_C(0x40000ffc)
#define OUTPUT_VA UINT64_C(0x40002ffc)
#define PTE_RW UINT64_C(0xc7)
#define EVU_ELEMENTS 128

static uint64_t root[512] __attribute__((aligned(4096)));
static uint64_t middle[512] __attribute__((aligned(4096)));
static volatile uint64_t leaves[512] __attribute__((aligned(4096)));
static uint8_t input_pages[3][4096] __attribute__((aligned(4096)));
static uint8_t output_pages[3][4096] __attribute__((aligned(4096)));
static uint32_t checkpoint[EVU_ELEMENTS] __attribute__((aligned(64)));
static volatile unsigned failures, fault_stage, fault_count;
static volatile uint64_t unexpected_cause, unexpected_address;

static uint64_t pte(const void *address, uint64_t flags) {
  return (((uintptr_t)address >> 12) << 10) | flags;
}

static uint32_t *element(uint8_t pages[3][4096], unsigned index) {
  return (uint32_t *)(index == 0 ? pages[0] + 4092 : pages[2] + (index - 1) * 4);
}

static void fill(uint8_t pages[3][4096], const uint32_t *values) {
  memset(pages, 0xa5, 3 * 4096);
  for (unsigned i = 0; i < EVU_ELEMENTS; ++i) *element(pages, i) = values[i];
}

static int guards_ok(uint8_t pages[3][4096]) {
  for (unsigned i = 0; i < 4092; ++i) if (pages[0][i] != 0xa5) return 0;
  for (unsigned i = 0; i < 4096; ++i) if (pages[1][i] != 0xa5) return 0;
  for (unsigned i = (EVU_ELEMENTS - 1) * 4; i < 4096; ++i)
    if (pages[2][i] != 0xa5) return 0;
  return 1;
}

static void machine_finish(void) __attribute__((noreturn, used));
static void machine_finish(void) {
  if (fault_count != 2) ++failures;
  printf("EVU virtual memory: faults=%u failures=%u cause=%lu address=0x%lx\n",
    fault_count, failures, unexpected_cause, unexpected_address);
  _halt(failures ? 1 : 0);
  while (1) {}
}

static void finish_after_trap(void) {
  uintptr_t target = (uintptr_t)machine_finish;
  uintptr_t status;
  __asm__ volatile ("csrr %0, mstatus" : "=r"(status));
  status = (status & ~(UINT64_C(3) << 11)) | (UINT64_C(3) << 11);
  __asm__ volatile ("csrw mepc, %0\ncsrw mstatus, %1" :: "r"(target), "r"(status) : "memory");
}

/* Avoid using core vector temporaries in the trap handler. The faulting
 * supervisor instruction may have live compiler-generated RVV values.
 */
static void trap_body(void) __attribute__((used, noinline, optnone));
static void trap_body(void) {
  uintptr_t cause, address;
  __asm__ volatile ("csrr %0, mcause\ncsrr %1, mtval" : "=r"(cause), "=r"(address));
  if (cause == 9 && fault_stage == 0) {
    finish_after_trap();
    return;
  }
  if (fault_stage == 1 && cause == 13 && address == 0x40001000) {
    /* A faulting load must preserve its destination register in full. */
    XV_STORE(3, checkpoint, 2);
    XV_WAIT();
    for (unsigned i = 0; i < EVU_ELEMENTS; ++i)
      if (checkpoint[i] != 0x12340000u + i) ++failures;
    leaves[1] = pte(input_pages[2], PTE_RW);
  } else if (fault_stage == 2 && cause == 15 && address == 0x40001000) {
    /* The second page denies writes: no first-page result may be published. */
    for (unsigned i = 0; i < EVU_ELEMENTS; ++i)
      if (*element(input_pages, i) != 0) ++failures;
    leaves[1] = pte(input_pages[2], PTE_RW);
  } else {
    ++failures;
    unexpected_cause = cause;
    unexpected_address = address;
    finish_after_trap();
    return;
  }
  ++fault_count;
  fault_stage = 0;
  __asm__ volatile ("fence rw, rw\nsfence.vma zero, zero" ::: "memory");
  /* Keep mepc unchanged: retry the complete EVU instruction. */
}

static void trap_entry(void) __attribute__((naked, aligned(4), used));
static void trap_entry(void) {
  __asm__ volatile (
    "addi sp, sp, -128\n"
    "sd ra, 0(sp)\nsd t0, 8(sp)\nsd t1, 16(sp)\nsd t2, 24(sp)\n"
    "sd a0, 32(sp)\nsd a1, 40(sp)\nsd a2, 48(sp)\nsd a3, 56(sp)\n"
    "sd a4, 64(sp)\nsd a5, 72(sp)\nsd a6, 80(sp)\nsd a7, 88(sp)\n"
    "sd t3, 96(sp)\nsd t4, 104(sp)\nsd t5, 112(sp)\nsd t6, 120(sp)\n"
    "call trap_body\n"
    "ld ra, 0(sp)\nld t0, 8(sp)\nld t1, 16(sp)\nld t2, 24(sp)\n"
    "ld a0, 32(sp)\nld a1, 40(sp)\nld a2, 48(sp)\nld a3, 56(sp)\n"
    "ld a4, 64(sp)\nld a5, 72(sp)\nld a6, 80(sp)\nld a7, 88(sp)\n"
    "ld t3, 96(sp)\nld t4, 104(sp)\nld t5, 112(sp)\nld t6, 120(sp)\n"
    "addi sp, sp, 128\nmret\n");
}

static void supervisor_main(void) __attribute__((noreturn, used));
static void supervisor_main(void) {
  uint32_t values[EVU_ELEMENTS];
  for (unsigned i = 0; i < EVU_ELEMENTS; ++i) values[i] = 0x12340000u + i;
  fill(input_pages, values);
  memset(output_pages, 0xa5, sizeof(output_pages));
  __asm__ volatile ("fence rw, rw" ::: "memory");
  XV_LOAD(3, (void *)INPUT_VA, 2);
  XV_STORE(3, (void *)OUTPUT_VA, 2);
  XV_WAIT();
  for (unsigned i = 0; i < EVU_ELEMENTS; ++i)
    if (*element(output_pages, i) != values[i]) ++failures;
  if (!guards_ok(input_pages) || !guards_ok(output_pages)) ++failures;

  memset(values, 0, sizeof(values));
  fill(input_pages, values);
  /* Reinstall the known register pattern before testing a faulting load. */
  for (unsigned i = 0; i < EVU_ELEMENTS; ++i) checkpoint[i] = 0x12340000u + i;
  XV_LOAD(3, checkpoint, 2);
  XV_WAIT();
  leaves[1] = 0;
  fault_stage = 1;
  __asm__ volatile ("fence rw, rw\nsfence.vma zero, zero" ::: "memory");
  XV_LOAD(3, (void *)INPUT_VA, 2);
  XV_WAIT();

  /* Restore nonzero data so a partial faulting store cannot go unnoticed. */
  XV_LOAD(3, checkpoint, 2);
  XV_WAIT();
  leaves[1] = pte(input_pages[2], 0xc3); // V/R/A/D, write denied.
  fault_stage = 2;
  __asm__ volatile ("fence rw, rw\nsfence.vma zero, zero" ::: "memory");
  XV_STORE(3, (void *)INPUT_VA, 2);
  XV_WAIT();
  for (unsigned i = 0; i < EVU_ELEMENTS; ++i)
    if (*element(input_pages, i) != checkpoint[i]) ++failures;
  if (!guards_ok(input_pages) || !guards_ok(output_pages)) ++failures;
  __asm__ volatile ("ecall" ::: "memory");
  while (1) {}
}

static void evu_vm_main(void) __attribute__((used, noreturn));
static void evu_vm_main(void) {
  root[1] = pte(middle, 1);
  middle[0] = pte((const void *)leaves, 1);
  /* Identity-map the program, stack and physical test buffers at 0x80000000. */
  root[2] = ((UINT64_C(0x80000000) >> 12) << 10) | 0xcf;
  leaves[0] = pte(input_pages[0], PTE_RW);
  leaves[1] = pte(input_pages[2], PTE_RW);
  leaves[2] = pte(output_pages[0], PTE_RW);
  leaves[3] = pte(output_pages[2], PTE_RW);
  const uintptr_t satp = (UINT64_C(8) << 60) | ((uintptr_t)root >> 12);
  const uintptr_t pmp_limit = UINT64_MAX;
  const uintptr_t pmp_permissions = 0x0f;
  const uintptr_t trap = (uintptr_t)trap_entry;
  /* Smrnmi suppresses normal traps while mnstatus.NMIE is clear. */
  __asm__ volatile ("csrsi 0x744, 8" ::: "memory");
  __asm__ volatile ("csrw pmpaddr0, %0\ncsrw pmpcfg0, %1\ncsrw mtvec, %2\ncsrw medeleg, zero\ncsrw mideleg, zero"
    :: "r"(pmp_limit), "r"(pmp_permissions), "r"(trap) : "memory");
  uintptr_t status;
  __asm__ volatile ("csrr %0, mstatus" : "=r"(status));
  status = (status & ~((UINT64_C(3) << 11) | (UINT64_C(1) << 17) | 8)) | (UINT64_C(1) << 11);
  const uintptr_t entry = (uintptr_t)supervisor_main;
  __asm__ volatile ("fence rw, rw\ncsrw satp, %0\nsfence.vma zero, zero\ncsrw mepc, %1\ncsrw mstatus, %2\nmret"
    :: "r"(satp), "r"(entry), "r"(status) : "memory");
  while (1) {}
}

int main(void) __attribute__((naked));
int main(void) {
  __asm__ volatile ("li t0, 0x6600\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\nli t0, 1\ncsrs 0x30c, t0\ntail evu_vm_main\n");
}
