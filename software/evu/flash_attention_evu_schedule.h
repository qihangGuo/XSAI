/* Intentionally unguarded: instantiate compile-time schedules over shared
 * buffers. No runtime mode branches are added inside the measured kernel. */
/* Compile-time register assignment: P0/P1 v0..v7, max v8..v11,
 * alpha/inverse scale v12..v15, running sum v16..v19, sum1 v20..v23,
 * shared scratch v24/v25, constants v26..v31. Once both score chunks
 * become probabilities, max can be reused for max_exp; alpha is dead
 * after the scalar sum update and becomes inverse_scale. No cross-row
 * reduction or VL>64 probability packing is used.
 */
#if EVU_FLASH_ROW_BATCH >= 2
#define FA_ROW_1(OP) OP(1, 2, 3, 9, 13, 17, 21)
#else
#define FA_ROW_1(OP)
#endif
#if EVU_FLASH_ROW_BATCH == 4
#define FA_ROW_23(OP) OP(2, 4, 5, 10, 14, 18, 22) OP(3, 6, 7, 11, 15, 19, 23)
#else
#define FA_ROW_23(OP)
#endif
#define FA_ROWS(OP) OP(0, 0, 1, 8, 12, 16, 20) FA_ROW_1(OP) FA_ROW_23(OP)

#if EVU_FLASH_SCALAR_OPS
#define FA_LOAD_ROW(lane, p0, p1, max, alpha, sum, sum1) do { \
  const unsigned row = base + (lane); \
  XV_LOAD(p0, scores[row], 2); \
  XV_LOAD(p1, scores[row] + CHUNK, 2); \
} while (0);
#if EVU_FLASH_CHUNK_MAX
#define FA_MAX_ROW(lane, p0, p1, max, alpha, sum, sum1) do { \
  XV_FREDUCE_MAX(sum, 26, p0, 2); \
  XV_FREDUCE_MAX(sum1, 26, p1, 2); \
  XV_FMAX_E0(max, sum, sum1); \
} while (0);
#else
#define FA_MAX_ROW(lane, p0, p1, max, alpha, sum, sum1) do { \
  XV_FMAX(24, p0, p1, 2); \
  XV_FREDUCE_MAX(max, 26, 24, 2); \
} while (0);
#endif
#if EVU_FLASH_FUSE_ALPHA
#define FA_SAVE_ALPHA(row, alpha) XV_STORE_E0(alpha, &row_alpha[row])
#else
#define FA_SAVE_ALPHA(row, alpha) do { \
  XV_LOAD(24, output[row], 2); \
  XV_FMUL_SCALAR(24, alpha, 24); \
  XV_STORE(24, output[row], 2); \
} while (0)
#endif
#define FA_STATE_ROW(lane, p0, p1, max, alpha, sum, sum1) do { \
  const unsigned row = base + (lane); \
  if (!first) { \
    XV_LOAD_E0(25, row_max[row]); \
    XV_FMAX_E0(max, 25, max); \
    XV_FSUB_E0(alpha, max, 25); \
    XV_EXP2_E0(alpha, alpha); \
    FA_SAVE_ALPHA(row, alpha); \
  } \
  XV_STORE_E0(max, row_max[row]); \
} while (0);
#define FA_PREPARE_ROW(lane, p0, p1, max, alpha, sum, sum1) \
  FA_LOAD_ROW(lane, p0, p1, max, alpha, sum, sum1) \
  FA_MAX_ROW(lane, p0, p1, max, alpha, sum, sum1) \
  FA_STATE_ROW(lane, p0, p1, max, alpha, sum, sum1)
#else
#define FA_PREPARE_ROW(lane, p0, p1, max, alpha, sum, sum1) do { \
  const unsigned row = base + (lane); \
  XV_LOAD(p0, scores[row], 2); \
  XV_LOAD(p1, scores[row] + CHUNK, 2); \
  XV_FMAX(24, p0, p1, 2); \
  XV_FREDUCE_MAX(max, 26, 24, 2); \
  XV_BROADCAST(max, max, 2); \
  if (!first) { \
    XV_LOAD(25, row_max[row], 2); \
    XV_FMAX(max, 25, max, 2); \
    XV_FSUB(alpha, max, 25, 2); \
    XV_EXP2(alpha, alpha, 2); \
    XV_LOAD(24, output[row], 2); \
    XV_FMUL(24, alpha, 24, 2); \
    XV_STORE(24, output[row], 2); \
  } \
  XV_STORE(max, row_max[row], 2); \
} while (0);
#endif

#define FA_SUBTRACT_P0(lane, p0, p1, max, alpha, sum, sum1) FA_FSUB_SCALAR(p0, max, p0);
#define FA_SUBTRACT_P1(lane, p0, p1, max, alpha, sum, sum1) FA_FSUB_SCALAR(p1, max, p1);
#define FA_EXP_P0(lane, p0, p1, max, alpha, sum, sum1) XV_EXP2(p0, p0, 2);
#define FA_EXP_P1(lane, p0, p1, max, alpha, sum, sum1) XV_EXP2(p1, p1, 2);
#if EVU_FLASH_CHUNK_MAX
/* The sum registers hold chunk maxima until their sum reductions start.
 * Subtract first while max is still the online row maximum. */
#define FA_CHUNK_DELTAS(lane, p0, p1, max, alpha, sum, sum1) do { \
  XV_FSUB_E0(sum, max, sum); \
  XV_FSUB_E0(sum1, max, sum1); \
} while (0);
#define FA_EXP_MAX_P0(lane, p0, p1, max, alpha, sum, sum1) XV_EXP2_E0(max, sum);
#define FA_EXP_MAX_P1(lane, p0, p1, max, alpha, sum, sum1) XV_EXP2_E0(max, sum1);
#define FA_PROB_MAX(max, p) do {} while (0)
#else
#define FA_CHUNK_DELTAS(lane, p0, p1, max, alpha, sum, sum1)
#define FA_EXP_MAX_P0(lane, p0, p1, max, alpha, sum, sum1)
#define FA_EXP_MAX_P1(lane, p0, p1, max, alpha, sum, sum1)
#define FA_PROB_MAX(max, p) XV_FREDUCE_MAX(max, 27, p, 2)
#endif
#define FA_REDUCE_P0(lane, p0, p1, max, alpha, sum, sum1) do { \
  XV_FREDUCE_SUM(sum, 27, p0, 2); \
  FA_PROB_MAX(max, p0); \
} while (0);
#define FA_SCALAR_P0(lane, p0, p1, max, alpha, sum, sum1) do { \
  const unsigned row = base + (lane); \
  if (!first) { \
    FA_LOAD_E0(24, &row_sum[row]); \
    FA_FMUL_E0(24, alpha, 24); \
    FA_FADD_E0(sum, sum, 24); \
  } \
  FA_FMUL_E0(24, 29, max); \
  FA_STORE_E0(24, &p_scale[0][row]); \
  FA_FMAX_E0(max, 31, max); \
  FA_FDIV_E0(alpha, max, 28); \
} while (0);
#define FA_QUANTIZE_P0(lane, p0, p1, max, alpha, sum, sum1) do { \
  FA_QUANTIZE(((base + (lane)) << 2) | 1, alpha, p0); \
} while (0);
#define FA_REDUCE_P1(lane, p0, p1, max, alpha, sum, sum1) do { \
  XV_FREDUCE_SUM(sum1, 27, p1, 2); \
  FA_PROB_MAX(max, p1); \
} while (0);
#define FA_SCALAR_P1(lane, p0, p1, max, alpha, sum, sum1) do { \
  const unsigned row = base + (lane); \
  FA_FADD_E0(sum, sum1, sum); \
  FA_STORE_E0(sum, &row_sum[row]); \
  FA_FMUL_E0(24, 29, max); \
  FA_STORE_E0(24, &p_scale[1][row]); \
  FA_FMAX_E0(max, 31, max); \
  FA_FDIV_E0(alpha, max, 28); \
} while (0);
#define FA_QUANTIZE_P1(lane, p0, p1, max, alpha, sum, sum1) do { \
  FA_QUANTIZE(((base + (lane)) << 2) | 2, alpha, p1); \
} while (0);
#if EVU_FLASH_EARLY_RECIP
#define FA_PREPARE_RECIP(lane, p0, p1, max, alpha, sum, sum1) do { \
  if (key_base + TILE == KV) { \
    XV_FDIV_E0(sum, sum, 30); \
    XV_STORE_E0(sum, &row_reciprocal[base + (lane)]); \
  } \
} while (0);
#else
#define FA_PREPARE_RECIP(lane, p0, p1, max, alpha, sum, sum1)
#endif

static void FA_SCHEDULE_SOFTMAX(unsigned key_base) {
  const unsigned first = key_base == 0;
  EVU_FLASH_PHASE_BEGIN(ROW_SOFTMAX_QUANTIZE, key_base / TILE, -1);
  for (unsigned base = 0; base < Q; base += EVU_FLASH_ROW_BATCH) {
#if EVU_FLASH_SCALAR_OPS
    FA_ROWS(FA_LOAD_ROW)
    FA_ROWS(FA_MAX_ROW)
    FA_ROWS(FA_STATE_ROW)
#else
    FA_ROWS(FA_PREPARE_ROW)
#endif
    FA_ROWS(FA_SUBTRACT_P0)
    FA_ROWS(FA_SUBTRACT_P1)
    FA_ROWS(FA_CHUNK_DELTAS)
    FA_ROWS(FA_EXP_MAX_P0)
    FA_ROWS(FA_EXP_P0)
    FA_ROWS(FA_EXP_P1)
    FA_ROWS(FA_REDUCE_P0)
    FA_BEGIN_SCALARS();
    FA_ROWS(FA_SCALAR_P0)
    FA_END_SCALARS();
    FA_ROWS(FA_QUANTIZE_P0)
    FA_ROWS(FA_EXP_MAX_P1)
    FA_ROWS(FA_REDUCE_P1)
    FA_BEGIN_SCALARS();
    FA_ROWS(FA_SCALAR_P1)
    FA_ROWS(FA_PREPARE_RECIP)
    FA_END_SCALARS();
    FA_ROWS(FA_QUANTIZE_P1)
  }
  sync_evu();
  EVU_FLASH_PHASE_END(ROW_SOFTMAX_QUANTIZE);
}

#if EVU_FLASH_MERGE_PV
#define FA_ADD_SECOND_PV(p0, p1, row) do { \
  XV_LOAD(p1, pv[1][row], 2); \
  XV_FADD(p0, p0, p1, 2); \
} while (0)
#else
#define FA_ADD_SECOND_PV(p0, p1, row) do {} while (0)
#endif
#if EVU_FLASH_FUSE_ALPHA
#define FA_SCALE_OLD_OUTPUT(p0, alpha, row) do { \
  if (EVU_FLASH_MERGE_PV || chunk == 0) { \
    XV_LOAD_E0(alpha, &row_alpha[row]); \
    XV_FMUL_SCALAR(p0, alpha, p0); \
  } \
} while (0)
#else
#define FA_SCALE_OLD_OUTPUT(p0, alpha, row) do {} while (0)
#endif
#define FA_OUTPUT_ROW(lane, p0, p1, max, alpha, sum, sum1) do { \
  const unsigned row = base + (lane); \
  if (first) { \
    XV_LOAD(p0, pv[0][row], 2); \
    XV_FADD(p0, 27, p0, 2); \
  } else { \
    XV_LOAD(p0, output[row], 2); \
    FA_SCALE_OLD_OUTPUT(p0, alpha, row); \
    XV_LOAD(p1, pv[0][row], 2); \
    XV_FADD(p0, p0, p1, 2); \
  } \
  FA_ADD_SECOND_PV(p0, p1, row); \
} while (0);
#if EVU_FLASH_EARLY_RECIP
#define FA_NORMALIZE_SCALAR(lane, p0, p1, max, alpha, sum, sum1) \
  XV_LOAD_E0(alpha, &row_reciprocal[base + (lane)]);
#else
#define FA_NORMALIZE_SCALAR(lane, p0, p1, max, alpha, sum, sum1) do { \
  FA_LOAD_E0(alpha, &row_sum[base + (lane)]); \
  FA_FDIV_E0(alpha, alpha, 30); \
} while (0);
#endif
#if EVU_FLASH_SCALAR_OPS
#define FA_NORMALIZE_VECTOR(lane, p0, p1, max, alpha, sum, sum1) \
  XV_FMUL_SCALAR(p0, alpha, p0);
#else
#define FA_NORMALIZE_VECTOR(lane, p0, p1, max, alpha, sum, sum1) do { \
  XV_BROADCAST(alpha, alpha, 2); \
  XV_FMUL(p0, alpha, p0, 2); \
} while (0);
#endif
#define FA_STORE_OUTPUT(lane, p0, p1, max, alpha, sum, sum1) \
  XV_STORE(p0, output[base + (lane)], 2);

static void FA_SCHEDULE_OUTPUT(unsigned key_base, unsigned chunk) {
  const unsigned first = key_base == 0 && (EVU_FLASH_MERGE_PV || chunk == 0);
  const unsigned last = key_base + TILE == KV && (EVU_FLASH_MERGE_PV || chunk == 1);
  const int profile_chunk = EVU_FLASH_MERGE_PV ? -1 : (int)chunk;
  (void)profile_chunk;
  if (last) {
    EVU_FLASH_PHASE_BEGIN(OUTPUT_ACCUMULATE_NORMALIZE, key_base / TILE, profile_chunk);
  } else {
    EVU_FLASH_PHASE_BEGIN(OUTPUT_ACCUMULATE, key_base / TILE, profile_chunk);
  }
  for (unsigned base = 0; base < Q; base += EVU_FLASH_ROW_BATCH) {
    // Keep (old_output + PV0) + PV1; first output still adds positive zero.
    FA_ROWS(FA_OUTPUT_ROW)
    if (last) {
      FA_BEGIN_SCALARS();
      FA_ROWS(FA_NORMALIZE_SCALAR)
      FA_END_SCALARS();
      FA_ROWS(FA_NORMALIZE_VECTOR)
    }
    FA_ROWS(FA_STORE_OUTPUT)
  }
  sync_evu();
  if (last) {
    EVU_FLASH_PHASE_END(OUTPUT_ACCUMULATE_NORMALIZE);
  } else {
    EVU_FLASH_PHASE_END(OUTPUT_ACCUMULATE);
  }
}

static void FA_SCHEDULE_KERNEL(void) {
  EVU_FLASH_PHASE_BEGIN(SETUP_CONSTANTS, -1, -1);
  // The first PV pass initializes every output row, including on repeated runs.
  config_e32(CHUNK);
  XV_LOAD(26, constants[0], 2);
  XV_LOAD(27, constants[1], 2);
  XV_LOAD(28, constants[2], 2);
  XV_LOAD(29, constants[3], 2);
  XV_LOAD(30, constants[4], 2);
  XV_LOAD(31, constants[5], 2);
  sync_evu();
  EVU_FLASH_PHASE_END(SETUP_CONSTANTS);
  for (unsigned key = 0; key < KV; key += TILE) {
    qk_matrix(key);
    FA_SCHEDULE_SOFTMAX(key);
    pv_matrix(key, 0);
    if (!EVU_FLASH_MERGE_PV) FA_SCHEDULE_OUTPUT(key, 0);
    pv_matrix(key, 1);
    FA_SCHEDULE_OUTPUT(key, 1);
  }
}

#undef FA_ROW_1
#undef FA_ROW_23
#undef FA_ROWS
#undef FA_LOAD_ROW
#undef FA_MAX_ROW
#undef FA_SAVE_ALPHA
#undef FA_STATE_ROW
#undef FA_PREPARE_ROW
#undef FA_SUBTRACT_P0
#undef FA_SUBTRACT_P1
#undef FA_EXP_P0
#undef FA_EXP_P1
#undef FA_CHUNK_DELTAS
#undef FA_EXP_MAX_P0
#undef FA_EXP_MAX_P1
#undef FA_PROB_MAX
#undef FA_REDUCE_P0
#undef FA_SCALAR_P0
#undef FA_QUANTIZE_P0
#undef FA_REDUCE_P1
#undef FA_SCALAR_P1
#undef FA_QUANTIZE_P1
#undef FA_PREPARE_RECIP
#undef FA_ADD_SECOND_PV
#undef FA_SCALE_OLD_OUTPUT
#undef FA_OUTPUT_ROW
#undef FA_NORMALIZE_SCALAR
#undef FA_NORMALIZE_VECTOR
#undef FA_STORE_OUTPUT
