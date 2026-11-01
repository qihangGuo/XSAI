/* One cold v4 profiled kernel: fused alpha, early reciprocal and chunk-max
 * reuse default on in the shared kernel. No FP64, baseline or warm-up. */
#define EVU_FLASH_PERF_ONLY
#define EVU_FLASH_BREAKDOWN_WARMUP 0
#include "flash_attention_evu_breakdown.c"
