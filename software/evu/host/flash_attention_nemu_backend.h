#ifndef EVU_FLASH_ATTENTION_NEMU_BACKEND_H
#define EVU_FLASH_ATTENTION_NEMU_BACKEND_H
/* Native semantic adapter for the exact guest C kernel. It executes existing
 * NEMU instructions in synchronous reference mode, copying host buffers into
 * an independent guest RAM. It makes no RTL or performance claims. The separate
 * evu_fp_quant_ref_test checks asynchronous write events and completion flags.
 */
#include <isa.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define HOST_CHECK(x) do { if (!(x)) { fprintf(stderr, "native FA %s:%d: %s\n", __FILE__, __LINE__, #x); exit(1); } } while (0)
static void (*host_copy_mem)(uint64_t, void *, size_t, bool);
static void (*host_copy_regs)(void *, bool);
static void (*host_copy_csrs)(void *, bool);
static void (*host_execute)(uint64_t);
static void (*host_write_csr)(uint32_t, uint64_t);
static uint64_t (*host_read_csr)(uint32_t);
static uint64_t host_code = 0x80008000, host_next_address = 0x80200000;
static unsigned host_vl = 128;
static void *host_matrix_output;
static uint64_t host_matrix_address;
static void *host_library;
static unsigned host_instructions;
static struct { const void *ptr; size_t size; uint64_t address; } host_buffers[4096];
static unsigned host_buffer_count;
static uint64_t host_buffer(const void *ptr, size_t size) {
  for (unsigned i = 0; i < host_buffer_count; ++i)
    if (host_buffers[i].ptr == ptr && host_buffers[i].size >= size) return host_buffers[i].address;
  HOST_CHECK(host_buffer_count < 4096 && host_next_address + size < 0x83f00000);
  uint64_t a = host_next_address; host_next_address += (size + 63) & ~(uint64_t)63;
  host_buffers[host_buffer_count++] = (typeof(host_buffers[0])){ptr, size, a};
  return a;
}
static uint32_t host_insn(unsigned op, unsigned f7, unsigned f3, unsigned rd, unsigned s1, unsigned s2) {
  return (f7 << 25) | (s2 << 20) | (s1 << 15) | (f3 << 12) | (rd << 7) | op;
}
static void host_run(unsigned op, unsigned f7, unsigned f3, unsigned rd, unsigned s1, unsigned s2,
                     uint64_t a, uint64_t b) {
  CPU_state state; host_copy_regs(&state, false);
  host_code += 4; state.pc = host_code; state.mcause = 0;
  state.gpr[10]._64 = a; state.gpr[11]._64 = b;
  uint32_t instruction = host_insn(op, f7, f3, rd, s1, s2);
  host_copy_mem(host_code, &instruction, 4, true); host_copy_regs(&state, true);
  host_execute(1); host_copy_regs(&state, false);
  if (state.mcause || state.pc != host_code + 4)
    fprintf(stderr, "native FA instr=0x%08x pc=0x%lx mcause=%lu\n", instruction, host_code, state.mcause);
  HOST_CHECK(state.mcause == 0 && state.pc == host_code + 4); ++host_instructions;
}
static void host_config(unsigned addr, uint64_t value) {
  host_write_csr(addr, value);
  if (addr == 0x8c0) host_vl = (unsigned)host_read_csr(addr);
}
static void host_vector_memory(unsigned op, unsigned reg, const void *pointer) {
  const size_t bytes = host_vl * 4;
  uint64_t a = host_buffer(pointer, bytes);
  if (op == 0x10) host_copy_mem(a, (void *)pointer, bytes, true);
  host_run(0x0b, op, 2, reg, 10, 0, a, 0);
  if (op == 0x11) host_copy_mem(a, (void *)pointer, bytes, false);
}
static void host_launch(void *out, unsigned sa, unsigned sb) {
  HOST_CHECK(!host_matrix_output);
  host_matrix_output = out; host_matrix_address = host_buffer(out, 128 * 128 * 4);
  host_run(0x0b, 0x18, 4, sb, 10, sa, host_matrix_address, 0);
}
static void host_fence(void) {
  host_run(0x0b, 0x1a, 0, 0, 0, 0, 0, 0);
  if (host_matrix_output) {
    host_copy_mem(host_matrix_address, host_matrix_output, 128 * 128 * 4, false);
    host_matrix_output = NULL;
  }
}
static void host_matrix_load(unsigned f7, unsigned reg, const void *ptr, unsigned stride) {
  uint64_t a = host_buffer(ptr, 128 * 64);
  host_copy_mem(a, (void *)ptr, 128 * 64, true);
  host_run(0x2b, f7, 1, reg, 10, 11, a, stride);
}
static void host_init(const char *path) {
  host_library = dlopen(path, RTLD_NOW | RTLD_LOCAL);
  if (!host_library) { fprintf(stderr, "%s\n", dlerror()); exit(1); }
#define H_SYMBOL(target, name) do { *(void **)(&(target)) = dlsym(host_library, name); HOST_CHECK(target); } while (0)
  void (*init)(void); void (*set_ram)(size_t);
  H_SYMBOL(init, "difftest_init"); H_SYMBOL(set_ram, "difftest_set_ramsize");
  H_SYMBOL(host_copy_mem, "difftest_memcpy"); H_SYMBOL(host_copy_regs, "difftest_regcpy");
  H_SYMBOL(host_copy_csrs, "difftest_csrcpy"); H_SYMBOL(host_execute, "difftest_exec");
  H_SYMBOL(host_write_csr, "evu_csr_write"); H_SYMBOL(host_read_csr, "evu_csr_read");
  set_ram(64 * 1024 * 1024); init();
  CPU_state state; host_copy_regs(&state, false);
  state.mode = 3; state.satp = 0; state.mtvec = 0x8000f000;
  state.mstatus = (UINT64_C(1) << 13) | (UINT64_C(1) << 9); host_copy_regs(&state, true);
  uint64_t csrs[4096]; host_copy_csrs(csrs, false);
  csrs[0xbc8] = 9; csrs[0x30c] |= 1; csrs[0x744] |= UINT64_C(1) << 3; host_copy_csrs(csrs, true);
}
#define XV_EVCFG 0x8c0
#define XV_EFFLAGS 0x8c1
#define XV_EVLENB 0xcd0
#define XV_EVSTATUS 0xcd2
#define XV_CONFIGURED (UINT64_C(1) << 40)
#define XV_CSR_WRITE(addr,value) host_config(addr,value)
#define XV_CSR_READ(addr) host_read_csr(addr)
#define XV_LOAD(v,p,sew) host_vector_memory(0x10,v,p)
#define XV_STORE(v,p,sew) host_vector_memory(0x11,v,p)
#define HOST_BINARY(op,d,a,b) host_run(0x0b,op,2,d,a,b,0,0)
#define XV_FADD(d,a,b,sew) HOST_BINARY(0x30,d,a,b)
#define XV_FSUB(d,a,b,sew) HOST_BINARY(0x31,d,a,b)
#define XV_FMUL(d,a,b,sew) HOST_BINARY(0x32,d,a,b)
#define XV_FDIV(d,a,b,sew) HOST_BINARY(0x34,d,a,b)
#define XV_FMAX(d,a,b,sew) HOST_BINARY(0x36,d,a,b)
#define XV_FREDUCE_SUM(d,a,b,sew) HOST_BINARY(0x37,d,a,b)
#define XV_FREDUCE_MAX(d,a,b,sew) HOST_BINARY(0x44,d,a,b)
#define XV_GATHER(d,a,b,sew) HOST_BINARY(0x47,d,a,b)
#define XV_GATHER_IMM(d,index,b,sew) HOST_BINARY(0x51,d,index,b)
#define XV_BROADCAST(d,b,sew) XV_GATHER_IMM(d,0,b,sew)
#define XV_EXP2(d,s,sew) HOST_BINARY(0x0f,d,0,s)
#define XV_FPQUANT_TO_CUTE(target,inv,src) host_run(0x0b,0x50,2,inv,10,src,target,0)
#define XV_LAUNCH_FP32(out,sa,sb) host_launch(out,sa,sb)
#define XV_WAIT() host_run(0x0b,0x19,0,0,0,0,0,0)
#define XV_FENCE() host_fence()
#define tr0 0
#define tr1 1
#define tr2 2
#define acc0 4
#define x2 2
#define AME_CONF_INT8 2
#define MSETCFG(r,v) host_run(0x2b,0x21,0,r,10,0,v,0)
#define AME_SET_I8() do { MSETCFG(0,2); MSETCFG(1,2); MSETCFG(4,4); } while(0)
#define MSYNCRESET() do {} while(0)
#define MSETTILEM(r,v) do { host_run(0x2b,0x11,0,0,10,0,v,0); (r)=v; } while(0)
#define MSETTILEN(r,v) do { host_run(0x2b,0x19,0,0,10,0,v,0); (r)=v; } while(0)
#define MSETTILEK(r,v) do { host_run(0x2b,0x09,0,0,10,0,v,0); (r)=v; } while(0)
#define MZERO_ACC(r) host_run(0x2b,0,3,r,0,0,0,0)
#define MLAE8(r,p,s) host_matrix_load(0,r,p,s)
#define MLBE8(r,p,s) host_matrix_load(8,r,p,s)
#define MQMA(d,a,b) host_run(0x2b,0,2,d,a,b,0,0)
#define MRELEASE() do {} while(0)
#define MACQUIRE(v) do {} while(0)
#endif
