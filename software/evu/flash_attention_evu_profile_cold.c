/* One cold profiled kernel, no FP64 reference, baseline or warm-up. */
#define EVU_FLASH_PERF_ONLY
#define EVU_FLASH_BREAKDOWN_WARMUP 0
#include "flash_attention_evu_breakdown.c"
