/* Correctness validation followed by one warmed, profiled kernel. */
#define EVU_FLASH_BREAKDOWN_WARMUP 1
#define EVU_FLASH_SKIP_BASELINE 1
#include "flash_attention_evu_breakdown.c"
