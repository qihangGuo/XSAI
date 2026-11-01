#include <stdint.h>

#ifndef EVU_FLASH_BREAKDOWN_WARMUP
#define EVU_FLASH_BREAKDOWN_WARMUP 1
#endif
#if EVU_FLASH_BREAKDOWN_WARMUP != 0 && EVU_FLASH_BREAKDOWN_WARMUP != 1
#error "EVU_FLASH_BREAKDOWN_WARMUP must be 0 or 1"
#endif
#ifndef EVU_FLASH_SKIP_BASELINE
#define EVU_FLASH_SKIP_BASELINE 0
#endif
#if EVU_FLASH_SKIP_BASELINE != 0 && EVU_FLASH_SKIP_BASELINE != 1
#error "EVU_FLASH_SKIP_BASELINE must be 0 or 1"
#endif
#ifdef EVU_FLASH_PERF_ONLY
#define EVU_FLASH_SKIP_REFERENCE
#endif

enum {
  FA_PHASE_SETUP_CONSTANTS,
  FA_PHASE_QK_SUBMIT,
  FA_PHASE_QK_WAIT_DRAIN,
  FA_PHASE_ROW_SOFTMAX_QUANTIZE,
  FA_PHASE_PV_PACK,
  FA_PHASE_PV_SUBMIT,
  FA_PHASE_PV_WAIT_DRAIN,
  FA_PHASE_OUTPUT_ACCUMULATE,
  FA_PHASE_OUTPUT_ACCUMULATE_NORMALIZE,
  FA_PHASE_COUNT
};

static void fa_phase_begin(unsigned phase, int tile, int chunk);
static void fa_phase_end(unsigned phase);
#define EVU_FLASH_PHASE_BEGIN(phase, tile, chunk) \
  fa_phase_begin(FA_PHASE_##phase, tile, chunk)
#define EVU_FLASH_PHASE_END(phase) fa_phase_end(FA_PHASE_##phase)
#define EVU_FLASH_NO_MAIN
#include "flash_attention_evu_test.c"

/* Reuse the kernel; full mode also keeps both FP64 oracles. The existing
 * phase-end fences provide drains; profiling adds no fences or kernel prints. */
enum { FA_MAX_SAMPLES = 32, FA_EXPECTED_SAMPLES = 19 + FA_OUTPUT_PASSES };
typedef struct {
  unsigned phase;
  int tile, chunk;
  uint64_t cycles, instructions;
} fa_sample_t;
static fa_sample_t fa_samples[FA_MAX_SAMPLES];
static volatile unsigned fa_profile_enabled;
static unsigned fa_sample_count, fa_phase_active, fa_profile_error;
static uint64_t fa_phase_cycle, fa_phase_instret;
static const char *const fa_phase_names[FA_PHASE_COUNT] = {
  "setup_constants", "qk_submit", "qk_wait_drain", "row_softmax_quantize",
  "pv_pack", "pv_submit", "pv_wait_drain", "output_accumulate",
  "output_accumulate_normalize"
};
static const unsigned fa_expected_calls[FA_PHASE_COUNT] = {
  1, 2, 2, 2, 4, 4, 4, FA_OUTPUT_PASSES - 1, 1
};

static __attribute__((noinline)) void fa_phase_begin(unsigned phase, int tile, int chunk) {
  if (!fa_profile_enabled) return;
  if (fa_phase_active || phase >= FA_PHASE_COUNT || fa_sample_count >= FA_MAX_SAMPLES) {
    fa_profile_error = 1;
    return;
  }
  fa_sample_t *sample = &fa_samples[fa_sample_count];
  sample->phase = phase;
  sample->tile = tile;
  sample->chunk = chunk;
  fa_phase_active = 1;
  fa_phase_cycle = read_cycle();
  fa_phase_instret = read_instret();
}

static __attribute__((noinline)) void fa_phase_end(unsigned phase) {
  if (!fa_profile_enabled) return;
  uint64_t instructions = read_instret(), cycles = read_cycle();
  if (!fa_phase_active || fa_sample_count >= FA_MAX_SAMPLES ||
      fa_samples[fa_sample_count].phase != phase) {
    fa_profile_error = 1;
    return;
  }
  fa_samples[fa_sample_count].cycles = cycles - fa_phase_cycle;
  fa_samples[fa_sample_count].instructions = instructions - fa_phase_instret;
  ++fa_sample_count;
  fa_phase_active = 0;
}

#ifndef EVU_FLASH_NATIVE
static unsigned fa_validate(const char *mode) {
#ifdef EVU_FLASH_PERF_ONLY
  unsigned failures = 0;
  uint64_t validation_cycles = 0;
#else
  uint64_t start = read_cycle();
  unsigned failures = check_output();
  uint64_t validation_cycles = read_cycle() - start;
#endif
  uint64_t status = XV_CSR_READ(XV_EVSTATUS), flags = XV_CSR_READ(XV_EFFLAGS);
  if ((status & 2) || (flags & 0x1c)) ++failures;
  if (failures)
    printf("[FA] FAIL %s st=%lx fl=%lx err=%u check_c=%lu\n", mode,
      (unsigned long)status, (unsigned long)flags, failures, (unsigned long)validation_cycles);
  return failures;
}

static uint64_t fa_measure(unsigned enabled, uint64_t *instructions) {
  fa_profile_enabled = enabled;
  fa_sample_count = fa_phase_active = fa_profile_error = 0;
  XV_CSR_WRITE(XV_EFFLAGS, 0);
  sync_evu();
  uint64_t c = read_cycle(), n = read_instret();
  flash_attention_evu();
  *instructions = read_instret() - n;
  uint64_t cycles = read_cycle() - c;
  fa_profile_enabled = 0;
  return cycles;
}

static unsigned fa_report(uint64_t baseline, uint64_t wall) {
  uint64_t cycles[FA_PHASE_COUNT] = {0};
  unsigned calls[FA_PHASE_COUNT] = {0};
  uint64_t accounted = 0;
  for (unsigned i = 0; i < fa_sample_count; ++i) {
    const fa_sample_t *sample = &fa_samples[i];
    if (sample->phase >= FA_PHASE_COUNT) { fa_profile_error = 1; continue; }
    cycles[sample->phase] += sample->cycles;
    ++calls[sample->phase];
  }
  for (unsigned phase = 0; phase < FA_PHASE_COUNT; ++phase) {
    accounted += cycles[phase];
    if (calls[phase] != fa_expected_calls[phase]) {
      fa_profile_error = 1;
      printf("[FA] FAIL phase=%s calls=%u/%u\n", fa_phase_names[phase],
        calls[phase], fa_expected_calls[phase]);
    }
  }
  if (fa_sample_count != FA_EXPECTED_SAMPLES || fa_phase_active || accounted > wall || !wall)
    fa_profile_error = 1;
  printf("[FA] qk=%lu/%lu sm=%lu pack=%lu pv=%lu/%lu\n",
    (unsigned long)cycles[FA_PHASE_QK_SUBMIT], (unsigned long)cycles[FA_PHASE_QK_WAIT_DRAIN],
    (unsigned long)cycles[FA_PHASE_ROW_SOFTMAX_QUANTIZE], (unsigned long)cycles[FA_PHASE_PV_PACK],
    (unsigned long)cycles[FA_PHASE_PV_SUBMIT], (unsigned long)cycles[FA_PHASE_PV_WAIT_DRAIN]);
  printf("[FA] setup=%lu out=%lu/%lu other=%lu samples=%u/%u err=%u\n",
    (unsigned long)cycles[FA_PHASE_SETUP_CONSTANTS], (unsigned long)cycles[FA_PHASE_OUTPUT_ACCUMULATE],
    (unsigned long)cycles[FA_PHASE_OUTPUT_ACCUMULATE_NORMALIZE],
    (unsigned long)(wall >= accounted ? wall - accounted : 0),
    fa_sample_count, FA_EXPECTED_SAMPLES, fa_profile_error);
#if !defined(EVU_FLASH_PERF_ONLY) && !EVU_FLASH_SKIP_BASELINE
  // This delta includes cache/order/layout effects, not just timer reads.
  int64_t delta = wall >= baseline ? (int64_t)(wall - baseline) : -(int64_t)(baseline - wall);
  printf("[FA] profile_minus_base=%ld\n", (long)delta);
#else
  (void)baseline;
#endif
  return fa_profile_error;
}

__attribute__((used, noinline)) static int flash_attention_breakdown_main(void) {
  const uint64_t required_caps = XV_CAP_FPQUANT_CUTE | XV_CAP_GATHER_IMM;
  if (XV_CSR_READ(XV_EVLENB) != 512 || (XV_CSR_READ(XV_EVCAP) & required_caps) != required_caps) {
    printf("[FA] FAIL unsupported EVU\n");
    return 1;
  }
#ifdef EVU_FLASH_PERF_ONLY
  const unsigned reference = 0, baseline_runs = 0;
#else
  const unsigned reference = 1, baseline_runs = !EVU_FLASH_SKIP_BASELINE;
#endif
  printf("[FA] " FA_KERNEL_NAME " q=%u/%u/%u rows=%u pv=%u sc=%u arm=%u%u%u w=%u ref=%u b=%u\n",
    Q, KV, D, EVU_FLASH_ROW_BATCH, EVU_FLASH_MERGE_PV, EVU_FLASH_SCALAR_OPS,
    EVU_FLASH_FUSE_ALPHA, EVU_FLASH_EARLY_RECIP, EVU_FLASH_CHUNK_MAX,
    EVU_FLASH_BREAKDOWN_WARMUP, reference, baseline_runs);
  uint64_t start = read_cycle();
  init_inputs();
  printf("[FA] init=%lu %s\n", (unsigned long)(read_cycle() - start), reference ? "ref" : "run");
#ifndef EVU_FLASH_PERF_ONLY
  start = read_cycle();
  make_reference();
  printf("[FA] ref_c=%lu run\n", (unsigned long)(read_cycle() - start));
#endif
  uint64_t instructions;
#if EVU_FLASH_BREAKDOWN_WARMUP
  uint64_t warmup = fa_measure(0, &instructions);
  if (fa_validate("warmup")) return 1;
  printf("[FA] warm c=%lu i=%lu\n",
    (unsigned long)warmup, (unsigned long)instructions);
#endif
  uint64_t baseline = 0;
#if !defined(EVU_FLASH_PERF_ONLY) && !EVU_FLASH_SKIP_BASELINE
  baseline = fa_measure(0, &instructions);
  if (fa_validate("baseline")) return 1;
  printf("[FA] base c=%lu i=%lu\n",
    (unsigned long)baseline, (unsigned long)instructions);
#endif
  uint64_t wall = fa_measure(1, &instructions);
  unsigned failures = fa_validate("profiled");
  failures += fa_report(baseline, wall);
  printf("[FA] %s c=%lu i=%lu w=%u ref=%u err=%u\n", failures ? "FAIL" : "PASS",
    (unsigned long)wall, (unsigned long)instructions, EVU_FLASH_BREAKDOWN_WARMUP, reference, failures);
  return failures != 0;
}

__attribute__((naked)) int main(void) {
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail flash_attention_breakdown_main\n");
}
#endif /* !EVU_FLASH_NATIVE */
