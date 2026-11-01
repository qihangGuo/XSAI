/* Best measured schedule: fused alpha and chunk maxima, with the reciprocal
 * computed during output update. Keep both FP64 checks and one warm-up. */
#ifndef EVU_FLASH_EARLY_RECIP
#define EVU_FLASH_EARLY_RECIP 0
#endif
#define EVU_FLASH_BREAKDOWN_WARMUP 1
#define EVU_FLASH_SKIP_BASELINE 1
#include "flash_attention_evu_breakdown.c"
