/* Compatibility include for the former design proposal. Candidate RTL now
 * implements these operations; software must check the EVCAP groups on its target.
 */
#ifndef EVU_CVT_EXTENSION_PROPOSAL_H
#define EVU_CVT_EXTENSION_PROPOSAL_H
#include "../../include/evu_intrinsics.h"
#include "../../include/evu_csr.h"
#define XV_EVCAP_CVT_RAW_EXTENSION XV_CAP_CVT_RAW_EXTENSION
#define XV_EVCAP_CVT_MX_EXTENSION XV_CAP_CVT_MX_EXTENSION
#define XV_EVCAP_CVT_BF16 XV_CAP_CVT_BF16
#endif
