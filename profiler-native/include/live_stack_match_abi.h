#ifndef MAZEWALL_LIVE_STACK_MATCH_ABI_H
#define MAZEWALL_LIVE_STACK_MATCH_ABI_H
#include "live_stack_context_abi.h"
#define MW_LIVE_MATCH_UNAVAILABLE 0u
#define MW_LIVE_MATCH_MATCH 1u
#define MW_LIVE_MATCH_MALFORMED 2u
typedef struct __attribute__((aligned(8))) mw_live_match_request_v1 {
    mw_live_request_v1 request;
    uint64_t expected_publication;
    uint32_t kind, flags_bits, mode_bits, dirfd_bits;
    uint64_t path_bits, reserved;
} mw_live_match_request_v1;
typedef struct __attribute__((aligned(8))) mw_live_match_result_v1 {
    uint32_t abi_version, struct_bytes, status, reserved;
} mw_live_match_result_v1;
#ifdef __cplusplus
extern "C" {
#endif
__attribute__((visibility("default"))) uint32_t mazewall_live_match_arguments_v1(
    const void* request, uint32_t request_bytes, void* result, uint32_t result_bytes);
#ifdef __cplusplus
}
#endif
#endif
