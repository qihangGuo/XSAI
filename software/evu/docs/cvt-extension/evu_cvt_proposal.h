/* Review proposal only: new selectors are NOT enabled by current RTL/NEMU. */
#ifndef EVU_CVT_EXTENSION_PROPOSAL_H
#define EVU_CVT_EXTENSION_PROPOSAL_H
#include <stdint.h>
#include "../../include/evu_intrinsics.h"
#ifndef XV_ENABLE_CVT_EXTENSION_PROPOSAL
#error "Review-only ISA: define XV_ENABLE_CVT_EXTENSION_PROPOSAL after matching RTL/model integration"
#endif

#define XV_CVT_U8_F32(vd, vs) XV_BINARY(0x27, vd, 0, vs, 2)
#define XV_CVT_F32_I8(vd, vs) XV_BINARY(0x28, vd, 0, vs, 2)
#define XV_CVT_F32_U8(vd, vs) XV_BINARY(0x29, vd, 0, vs, 2)
#define XV_CVT_E2M1_F32(vd, vs) XV_BINARY(0x2a, vd, 0, vs, 2)
#define XV_CVT_F32_E5M2(vd, vs) XV_BINARY(0x2b, vd, 0, vs, 2)
#define XV_CVT_F32_E2M1(vd, vs) XV_BINARY(0x2c, vd, 0, vs, 2)
#define XV_CVT_BF16_F32(vd, vs) XV_BINARY(0x2d, vd, 0, vs, 2)
#define XV_CVT_F32_BF16(vd, vs) XV_BINARY(0x2e, vd, 0, vs, 2)
#define XV_CVT_MXE5M2_F32(vd, scale, vs) XV_BINARY(0x76, vd, scale, vs, 2)
#define XV_CVT_F32_MXE4M3(vd, scale, vs) XV_BINARY(0x77, vd, scale, vs, 2)
#define XV_CVT_F32_MXE5M2(vd, scale, vs) XV_BINARY(0x78, vd, scale, vs, 2)
#define XV_CVT_F32_MXFP4(vd, scale, vs) XV_BINARY(0x79, vd, scale, vs, 2)

#define XV_EVCAP_CVT_RAW_EXTENSION (UINT64_C(1) << 30)
#define XV_EVCAP_CVT_MX_EXTENSION (UINT64_C(1) << 31)
#define XV_EVCAP_CVT_BF16 (UINT64_C(1) << 32)
#endif
