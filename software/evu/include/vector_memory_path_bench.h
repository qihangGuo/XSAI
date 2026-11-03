#ifndef VECTOR_MEMORY_PATH_BENCH_H
#define VECTOR_MEMORY_PATH_BENCH_H

/* Shared diagnostic driver. The including test supplies mp_backend_* helpers
 * and reuses its existing online softmax's input, reset and timing helpers. */
#ifndef MEM_PATH_CASE
#define MEM_PATH_CASE 0
#endif
#ifndef MEM_PATH_CACHE
#define MEM_PATH_CACHE -1
#endif
#ifndef MEM_PATH_EVICT_KIB
#define MEM_PATH_EVICT_KIB 256
#endif
#ifndef MEM_PATH_EVICT_PASSES
#define MEM_PATH_EVICT_PASSES 2
#endif
#ifndef MEM_PATH_ITERS
#define MEM_PATH_ITERS 8
#endif
#ifndef MEM_PATH_WARMUP
#define MEM_PATH_WARMUP 1
#endif
#ifndef MEM_PATH_GROUP
#define MEM_PATH_GROUP 0
#endif
#ifndef MEM_PATH_TRANSPORT_CHECK
#define MEM_PATH_TRANSPORT_CHECK 0
#endif
#if MEM_PATH_TRANSPORT_CHECK != 0 && MEM_PATH_TRANSPORT_CHECK != 1
#error "MEM_PATH_TRANSPORT_CHECK must be 0 or 1"
#endif
#if MEM_PATH_GROUP < 0 || MEM_PATH_GROUP > 3
#error "MEM_PATH_GROUP must be 0 (all), 1 (softmax), 2 (memory), or 3 (resident)"
#endif
#if MEM_PATH_CASE < 0 || MEM_PATH_CASE > 14
#error "MEM_PATH_CASE must be 0 (all) or 1..14"
#endif
#if MEM_PATH_CACHE < -1 || MEM_PATH_CACHE > 1
#error "MEM_PATH_CACHE must be -1 (both), 0 (CPU touch), or 1 (eviction attempt)"
#endif
#if MEM_PATH_EVICT_KIB < 64 || MEM_PATH_EVICT_KIB % 4 || MEM_PATH_EVICT_PASSES < 1
#error "eviction buffer must be at least 64 KiB, a multiple of 4 KiB, with positive passes"
#endif
#if MEM_PATH_ITERS < 1 || (MEM_PATH_WARMUP != 0 && MEM_PATH_WARMUP != 1)
#error "iterations must be positive and warmup must be 0 or 1"
#endif

enum {
  MP_BYTES = 32 * 1024, MP_FLOATS = MP_BYTES / sizeof(float),
  MP_STATE_FLOATS = 128, MP_LINE = 64,
  MP_EVICT_BYTES = MEM_PATH_EVICT_KIB * 1024
};
static float mp_destination[MP_FLOATS] ALIGN;
static uint32_t mp_eviction[MP_EVICT_BYTES / sizeof(uint32_t)]
  __attribute__((aligned(4096)));
static float mp_snapshot[16] ALIGN;
static float mp_store_seed[MP_NATIVE_VL] ALIGN;
static volatile uint64_t mp_sink;
static const char *const mp_names[] = {
  "all", "soft0", "soft1", "cpu_probe", "load", "store", "copy",
  "state4", "state16", "state64", "exp_shift", "max", "sum", "chain", "line_load"
};

static unsigned mp_resident(unsigned id) { return id >= 10 && id <= 13; }

static void mp_touch(const void *base, unsigned bytes) {
  const volatile float *data = (const volatile float *)base;
  uint64_t value = 0;
  for (unsigned i = 0; i < bytes / sizeof(float); i += MP_LINE / sizeof(float))
    value += float_bits(data[i]);
  mp_sink = value;
}

static void mp_condition(unsigned id, unsigned evict) {
  /* "cpu_touch" is a preparation recipe, not a claim of 100% L1 hits.
   * memcpy/reset leaves CPU-owned dirty lines, as in the primary benchmark. */
  if (id != 5) mp_touch(scores.data,
    id >= 7 && id <= 9 ? MP_STATE_FLOATS * sizeof(float) : MP_BYTES);
  if (id == 5 || id == 6 || (id >= 7 && id <= 9))
    mp_touch(mp_destination, id >= 7 ? MP_STATE_FLOATS * sizeof(float) : MP_BYTES);
  if (id <= 2) {
    mp_touch(row_max.data, sizeof(row_max.data));
    mp_touch(row_sum.data, sizeof(row_sum.data));
    mp_touch(row_alpha.data, sizeof(row_alpha.data));
  }
  if (evict) {
    /* Reverse the second sweep to reduce one-way stream-prefetch effects.
     * Replacement, indexing and prefetch mean eviction is never guaranteed. */
    const volatile uint32_t *data = mp_eviction;
    uint64_t value = 0;
    const unsigned lines = MP_EVICT_BYTES / MP_LINE;
    for (unsigned pass = 0; pass < MEM_PATH_EVICT_PASSES; ++pass)
      for (unsigned i = 0; i < lines; ++i) {
        unsigned line = pass & 1 ? lines - 1 - i : i;
        value += data[line * (MP_LINE / sizeof(uint32_t))];
      }
    mp_sink = value;
  }
  synchronize();
}

static void mp_setup(unsigned id) {
  if (id <= 2) reset_case();
  else {
    unsigned bytes = mp_resident(id) ? 4 * MP_RESIDENT_VL * sizeof(float) :
      id >= 7 && id <= 9 ? MP_STATE_FLOATS * sizeof(float) :
      id == 5 ? MP_NATIVE_VL * sizeof(float) : MP_BYTES;
    memcpy(scores.data, input_template, bytes);
  }
  if (id == 5 || id == 6 || (id >= 7 && id <= 9))
    memset(mp_destination, 0,
      id >= 7 && id <= 9 ? MP_STATE_FLOATS * sizeof(float) : sizeof(mp_destination));
  synchronize();
  mp_backend_setup(id);
  mp_backend_ready(id);
  synchronize();
}

static void mp_work(unsigned id) {
  if (id == 1 || id == 2) online_update(id - 1);
  else if (id == 3) mp_touch(scores.data, MP_BYTES);
  else mp_backend_work(id);
}

static uint64_t mp_payload(unsigned id) {
  if (id == 3) return MP_BYTES / MP_LINE * sizeof(uint32_t);
  if (id == 4 || id == 5 || id == 14) return MP_BYTES;
  if (id == 6) return 2 * MP_BYTES;
  if (id >= 7 && id <= 9) return 2 * MP_STATE_FLOATS * sizeof(float);
  return 0;
}

static unsigned mp_validate(unsigned id) {
  unsigned errors = 0;
#if MEM_PATH_TRANSPORT_CHECK
  /* Cheap transport smoke checks, never an independent FP64 softmax oracle.
   * Runs after timing, so CPU reads cannot warm the measured invocation. */
  if (id == 6 || (id >= 7 && id <= 9)) {
    unsigned n = id == 6 ? MP_FLOATS : MP_STATE_FLOATS;
    for (unsigned i = 0; i < n; ++i)
      errors += float_bits(mp_destination[i]) != float_bits(input_template[i]);
  } else if (id == 5) {
    for (unsigned i = 0; i < MP_FLOATS; ++i)
      errors += float_bits(mp_destination[i]) != float_bits(mp_store_seed[0]);
  }
#else
  (void)id;
#endif
  return errors;
}

static int mp_run(unsigned id, unsigned cache) {
  mp_setup(id);
  if (!mp_resident(id)) mp_condition(id, cache);
  mp_backend_clear();
  const uint64_t i0 = read_instret(), begin = read_cycle();
  mp_work(id);
  mp_backend_finish(id);
  synchronize();
  const uint64_t end = read_cycle(), instructions = read_instret() - i0;
  const unsigned flags = mp_backend_flags();
  unsigned errors = mp_backend_errors() + !!(flags & 0x1c) + (end <= begin);
  if (id >= 4) {
    mp_backend_snapshot(id);
    synchronize();
    mp_sink = float_bits(mp_snapshot[0]);
  }
  errors += mp_validate(id);
  const uint64_t cycles = end - begin;
  const uint64_t bytes = mp_payload(id);
  const uint64_t bpc = cycles ? bytes * 1000 / cycles : 0;
  printf("[MP-" MP_ENGINE "] k=%s cache=%s c=%lu i=%lu b=%lu bc=%lu.%03lu vm=%d"
    " n=%u t=%lu:%lu fl=%x err=%u\n", mp_names[id],
    mp_resident(id) ? "resident" : cache ? "evict_try" : "cpu_touch",
    (unsigned long)cycles, (unsigned long)instructions, (unsigned long)bytes,
    (unsigned long)(bpc / 1000), (unsigned long)(bpc % 1000),
    mp_backend_mem_commands(id),
    mp_resident(id) ? (unsigned)(4 * MP_RESIDENT_VL * MEM_PATH_ITERS) :
      id <= 2 ? ELEMENTS : 0,
    (unsigned long)begin, (unsigned long)end, flags, errors);
  return errors != 0;
}

__attribute__((used, noinline)) static int mp_test_main(void) {
  mp_backend_init();
  if (mp_backend_check()) return 1;
  initialize_input();
  /* Only one data initialization; eviction traffic is outside every timer. */
  for (unsigned i = 0; i < MP_EVICT_BYTES / sizeof(uint32_t); i += MP_LINE / sizeof(uint32_t))
    mp_eviction[i] = i ^ 0x31415926u;
  for (unsigned i = 0; i < MP_FLOATS; ++i) mp_destination[i] = 0.0f;
  for (unsigned i = 0; i < MP_NATIVE_VL; ++i) mp_store_seed[i] = 1.25f;
  synchronize();
  printf("[MP-" MP_ENGINE "] bytes=%u native_vl=%u resident_vl=%u it=%u w=%u"
    " evict_kib=%u passes=%u case=%u cache=%d group=%u check=%u hwc=emu_global fp64=0\n",
    (unsigned)MP_BYTES, (unsigned)MP_NATIVE_VL, (unsigned)MP_RESIDENT_VL,
    (unsigned)MEM_PATH_ITERS, (unsigned)MEM_PATH_WARMUP,
    (unsigned)MEM_PATH_EVICT_KIB, (unsigned)MEM_PATH_EVICT_PASSES,
    (unsigned)MEM_PATH_CASE, (int)MEM_PATH_CACHE, (unsigned)MEM_PATH_GROUP,
    (unsigned)MEM_PATH_TRANSPORT_CHECK);
  const uint64_t empty_start = read_cycle();
  synchronize();
  const uint64_t empty_cycles = read_cycle() - empty_start;
  printf("[MP-" MP_ENGINE "] empty=%lu cache_state_unverified=1 vm=static_vector_memory_commands\n",
    (unsigned long)empty_cycles);
  for (unsigned id = 1; id <= 14; ++id) {
    if (MEM_PATH_CASE && id != MEM_PATH_CASE) continue;
    if (!MEM_PATH_CASE && ((MEM_PATH_GROUP == 1 && id > 3) ||
        (MEM_PATH_GROUP == 2 && (id <= 3 || mp_resident(id))) ||
        (MEM_PATH_GROUP == 3 && !mp_resident(id)))) continue;
    if (MEM_PATH_WARMUP) {
      mp_setup(id);
      if (!mp_resident(id)) mp_condition(id, 0);
      mp_backend_clear();
      mp_work(id);
      mp_backend_finish(id);
      synchronize();
      if (mp_backend_errors() || (mp_backend_flags() & 0x1c)) return 1;
    }
    for (unsigned cache = 0; cache <= 1; ++cache) {
      if (mp_resident(id) && cache) continue;
      if (!mp_resident(id) && MEM_PATH_CACHE >= 0 && cache != (unsigned)MEM_PATH_CACHE) continue;
      if (mp_run(id, cache)) return 1;
    }
  }
  printf("[MP-" MP_ENGINE "] DONE\n");
  return 0;
}

#endif
