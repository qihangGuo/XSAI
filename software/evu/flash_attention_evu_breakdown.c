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
  printf("[EVU-FLASH-BREAKDOWN] check=%s validation_cycles=%lu "
         "evstatus=0x%lx efflags=0x%lx failures=%u\n", mode,
    (unsigned long)validation_cycles, (unsigned long)status, (unsigned long)flags, failures);
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

static uint64_t fa_pct_x100(uint64_t cycles, uint64_t wall) {
  return wall ? cycles * UINT64_C(10000) / wall : 0;
}

#ifndef EVU_FLASH_PERF_ONLY
static void fa_print_group(const char *name, uint64_t cycles, uint64_t wall) {
  uint64_t pct = fa_pct_x100(cycles, wall);
  printf("[EVU-FLASH-BREAKDOWN] group=%s cycles=%lu wall_pct=%lu.%02lu\n",
    name, (unsigned long)cycles, (unsigned long)(pct / 100), (unsigned long)(pct % 100));
}
#endif

static unsigned fa_report(uint64_t baseline, uint64_t wall) {
  uint64_t cycles[FA_PHASE_COUNT] = {0}, instructions[FA_PHASE_COUNT] = {0};
  unsigned calls[FA_PHASE_COUNT] = {0};
  uint64_t accounted = 0, accounted_instructions = 0;
  for (unsigned i = 0; i < fa_sample_count; ++i) {
    const fa_sample_t *sample = &fa_samples[i];
    if (sample->phase >= FA_PHASE_COUNT) { fa_profile_error = 1; continue; }
    cycles[sample->phase] += sample->cycles;
    instructions[sample->phase] += sample->instructions;
    ++calls[sample->phase];
#ifndef EVU_FLASH_PERF_ONLY
    uint64_t pct = fa_pct_x100(sample->cycles, wall);
    printf("[EVU-FLASH-BREAKDOWN] sample=%u phase=%s tile=%d chunk=%d "
           "cycles=%lu instret=%lu wall_pct=%lu.%02lu\n", i,
      fa_phase_names[sample->phase], sample->tile, sample->chunk,
      (unsigned long)sample->cycles, (unsigned long)sample->instructions,
      (unsigned long)(pct / 100), (unsigned long)(pct % 100));
#endif
  }
  for (unsigned phase = 0; phase < FA_PHASE_COUNT; ++phase) {
    accounted += cycles[phase];
    accounted_instructions += instructions[phase];
    if (calls[phase] != fa_expected_calls[phase]) fa_profile_error = 1;
    uint64_t pct = fa_pct_x100(cycles[phase], wall);
    printf("[EVU-FLASH-BREAKDOWN] phase=%s calls=%u cycles=%lu instret=%lu "
           "wall_pct=%lu.%02lu\n", fa_phase_names[phase], calls[phase],
      (unsigned long)cycles[phase], (unsigned long)instructions[phase],
      (unsigned long)(pct / 100), (unsigned long)(pct % 100));
  }
  if (fa_sample_count != FA_EXPECTED_SAMPLES || fa_phase_active || accounted > wall || !wall)
    fa_profile_error = 1;
#ifndef EVU_FLASH_PERF_ONLY
  fa_print_group("setup_constants", cycles[FA_PHASE_SETUP_CONSTANTS], wall);
  fa_print_group("qk_submit_wait_drain",
    cycles[FA_PHASE_QK_SUBMIT] + cycles[FA_PHASE_QK_WAIT_DRAIN], wall);
  fa_print_group("row_softmax_quantize", cycles[FA_PHASE_ROW_SOFTMAX_QUANTIZE], wall);
  fa_print_group("pv_pack_submit_wait_drain", cycles[FA_PHASE_PV_PACK] +
    cycles[FA_PHASE_PV_SUBMIT] + cycles[FA_PHASE_PV_WAIT_DRAIN], wall);
  fa_print_group("output_accumulate", cycles[FA_PHASE_OUTPUT_ACCUMULATE], wall);
  fa_print_group("output_accumulate_normalize", cycles[FA_PHASE_OUTPUT_ACCUMULATE_NORMALIZE], wall);
#endif
  printf("[EVU-FLASH-BREAKDOWN] samples=%u expected_samples=%u accounted_cycles=%lu "
         "outside_phases_cycles=%lu accounted_instret=%lu profile_errors=%u\n",
    fa_sample_count, FA_EXPECTED_SAMPLES, (unsigned long)accounted,
    (unsigned long)(wall >= accounted ? wall - accounted : 0),
    (unsigned long)accounted_instructions, fa_profile_error);
#if !defined(EVU_FLASH_PERF_ONLY) && !EVU_FLASH_SKIP_BASELINE
  // This delta includes cache/order/layout effects, not just timer reads.
  int64_t delta = wall >= baseline ? (int64_t)(wall - baseline) : -(int64_t)(baseline - wall);
  uint64_t magnitude = wall >= baseline ? wall - baseline : baseline - wall;
  uint64_t pct = fa_pct_x100(magnitude, baseline);
  printf("[EVU-FLASH-BREAKDOWN] profile_minus_baseline_cycles=%ld "
         "delta_pct=%s%lu.%02lu note=includes_instrumentation_and_run_variation\n",
    (long)delta, delta < 0 ? "-" : "", (unsigned long)(pct / 100), (unsigned long)(pct % 100));
#else
  (void)baseline;
#endif
  return fa_profile_error;
}

__attribute__((used, noinline)) static int flash_attention_breakdown_main(void) {
  const uint64_t required_caps = XV_CAP_FPQUANT_CUTE | XV_CAP_GATHER_IMM;
  if (XV_CSR_READ(XV_EVLENB) != 512 || (XV_CSR_READ(XV_EVCAP) & required_caps) != required_caps) {
    printf("[EVU-FLASH-BREAKDOWN] requires VLEN4096, FPQUANT-to-CUTE and immediate gather\n");
    return 1;
  }
#ifdef EVU_FLASH_PERF_ONLY
  printf("[EVU-FLASH-BREAKDOWN] mode=performance_only q=%u kv=%u d=%u warmup=%u "
         "baseline_runs=0 profiled_runs=1 fp64_reference=0 output_accuracy_check=0 "
         "existing_fences_only=1 timing_state=%s\n", Q, KV, D, EVU_FLASH_BREAKDOWN_WARMUP,
    EVU_FLASH_BREAKDOWN_WARMUP ? "after_warmup" : "no_warmup");
#else
  printf("[EVU-FLASH-BREAKDOWN] q=%u kv=%u d=%u warmup=%u baseline_runs=%u "
         "profiled_runs=1 correctness=1 existing_fences_only=1\n", Q, KV, D,
    EVU_FLASH_BREAKDOWN_WARMUP, EVU_FLASH_SKIP_BASELINE ? 0 : 1);
#endif
  printf("[EVU-FLASH-BREAKDOWN] division=%s broadcast=%s "
         "scale_divisions=%u normalize_divisions=%u\n",
    EVU_FLASH_SCALAR_OPS ? "instruction_local_element_zero" : "single_lane_scale_reciprocal_broadcast",
    EVU_FLASH_SCALAR_OPS ? "scalar_operand" : "gather_imm", Q * (KV / CHUNK), Q);
  printf("[EVU-FLASH-BREAKDOWN] kernel=" FA_KERNEL_NAME " profile_schema=" FA_PROFILE_SCHEMA " "
         "row_batch=%u compact_state=1 pv_pair=%u output_passes=%u scalar_ops=%u "
         "scale_vl_switches=%u normalize_vl_switches=%u\n",
    EVU_FLASH_ROW_BATCH, EVU_FLASH_MERGE_PV, FA_OUTPUT_PASSES, EVU_FLASH_SCALAR_OPS,
    FA_SCALE_VL_SWITCHES, FA_NORMALIZE_VL_SWITCHES);
  uint64_t start = read_cycle();
  init_inputs();
  printf("[EVU-FLASH-BREAKDOWN] init_cycles=%lu\n", (unsigned long)(read_cycle() - start));
#ifndef EVU_FLASH_PERF_ONLY
  printf("[EVU-FLASH-BREAKDOWN] reference begin\n");
  start = read_cycle();
  make_reference();
  printf("[EVU-FLASH-BREAKDOWN] reference done cycles=%lu "
         "unique_queries=%u/%u unique_value_columns=%u/%u\n",
    (unsigned long)(read_cycle() - start), reference_unique_queries, Q, reference_unique_columns, D);
#endif
  uint64_t instructions;
#if EVU_FLASH_BREAKDOWN_WARMUP
  printf("[EVU-FLASH-BREAKDOWN] warmup begin\n");
  uint64_t warmup = fa_measure(0, &instructions);
  if (fa_validate("warmup")) return 1;
  printf("[EVU-FLASH-BREAKDOWN] mode=warmup wall_cycles=%lu instret=%lu\n",
    (unsigned long)warmup, (unsigned long)instructions);
#endif
  uint64_t baseline = 0;
#if !defined(EVU_FLASH_PERF_ONLY) && !EVU_FLASH_SKIP_BASELINE
  printf("[EVU-FLASH-BREAKDOWN] baseline begin\n");
  baseline = fa_measure(0, &instructions);
  if (fa_validate("baseline")) return 1;
  printf("[EVU-FLASH-BREAKDOWN] mode=hooks_disabled e2e_cycles=%lu e2e_instret=%lu\n",
    (unsigned long)baseline, (unsigned long)instructions);
#endif
  printf("[EVU-FLASH-BREAKDOWN] profile begin\n");
  uint64_t wall = fa_measure(1, &instructions);
  unsigned failures = fa_validate("profiled");
  printf("[EVU-FLASH-BREAKDOWN] mode=profiled wall_cycles=%lu wall_instret=%lu\n",
    (unsigned long)wall, (unsigned long)instructions);
  failures += fa_report(baseline, wall);
#ifdef EVU_FLASH_PERF_ONLY
  printf("[EVU-FLASH-BREAKDOWN] result=%s failures=%u fp64_checked=0\n",
    failures ? "FAIL" : "PASS", failures);
#else
  printf("[EVU-FLASH-BREAKDOWN] result=%s failures=%u fp64_checked=1 warmup_checked=%u\n",
    failures ? "FAIL" : "PASS", failures, EVU_FLASH_BREAKDOWN_WARMUP);
#endif
  return failures != 0;
}

__attribute__((naked)) int main(void) {
  __asm__ volatile ("li t0, 0x2200\ncsrs mstatus, t0\nli t0, 9\ncsrw 0xbc8, t0\ntail flash_attention_breakdown_main\n");
}
#endif /* !EVU_FLASH_NATIVE */
