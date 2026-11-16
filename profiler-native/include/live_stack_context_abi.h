#ifndef MAZEWALL_LIVE_STACK_CONTEXT_ABI_H
#define MAZEWALL_LIVE_STACK_CONTEXT_ABI_H

#include <stdint.h>

#define MW_LIVE_ABI_VERSION 1u
#define MW_LIVE_MAX_FRAMES 128u
#define MW_LIVE_MAX_WORKERS 256u
#define MW_LIVE_MAX_CLASSES 512u
#define MW_LIVE_MAX_METHODS 4096u
#define MW_LIVE_MAX_ID UINT64_C(1152921504606846975)

#define MW_LIVE_AVAILABLE 0u
#define MW_LIVE_ABSENT 1u
#define MW_LIVE_INCOMPLETE 2u
#define MW_LIVE_BUSY 3u
#define MW_LIVE_STALE 4u
#define MW_LIVE_UNSUPPORTED 5u
#define MW_LIVE_INVALID_ARGUMENT 6u
#define MW_LIVE_EXHAUSTED 7u
#define MW_LIVE_INTERNAL_FAILURE 8u

#define MW_LIVE_NONE 0u
#define MW_LIVE_NO_TASK 1u
#define MW_LIVE_NO_ACTIVE_INVOCATION 2u
#define MW_LIVE_CAPTURE_FAILED 3u
#define MW_LIVE_OVERFLOW 4u
#define MW_LIVE_UNKNOWN_METHOD 5u
#define MW_LIVE_NESTED_OR_CALLBACK 6u
#define MW_LIVE_SLOT_CONTENDED 7u
#define MW_LIVE_IDENTITY_MISMATCH 8u
#define MW_LIVE_GENERATION_INVALID 9u
#define MW_LIVE_SHUTDOWN 10u
#define MW_LIVE_MISSING_BINDING 11u
#define MW_LIVE_RUNTIME_MISMATCH 12u
#define MW_LIVE_ABI_MISMATCH 13u
#define MW_LIVE_CAPACITY 14u
#define MW_LIVE_ID_EXHAUSTED 15u
#define MW_LIVE_MALFORMED 16u
#define MW_LIVE_REGISTRATION_MISSING 17u
#define MW_LIVE_NATIVE_FAILURE 18u
#define MW_LIVE_RETRANSFORM_UNAVAILABLE 19u
#define MW_LIVE_INSTALLATION_REVOKED 20u
#define MW_LIVE_NOT_PHYSICALLY_RETIRED 21u
#define MW_LIVE_CLEANUP_PENDING 22u

#define MW_LIVE_COPY 0u
#define MW_LIVE_RESERVE_INSTALLATION 1u
#define MW_LIVE_SEAL_ENROLLMENT 2u
#define MW_LIVE_REGISTER_CURRENT_WORKER 3u
#define MW_LIVE_BEGIN_CURRENT_TASK 4u
#define MW_LIVE_END_CURRENT_TASK 5u
#define MW_LIVE_INVALIDATE_INSTALLATION 6u
#define MW_LIVE_RETIRE_WORKER 7u
#define MW_LIVE_RETIRE_INSTALLATION 8u

typedef struct __attribute__((aligned(8))) mw_live_request_v1 {
    uint32_t abi_version, struct_bytes, operation, reserved0;
    uint64_t process_cookie, process_start_ticks, installation_id, worker_id;
    uint64_t task_id, code_generation, invocation_id;
    uint32_t tgid, tid, worker_capacity, reserved1;
} mw_live_request_v1;

typedef struct __attribute__((aligned(8))) mw_live_frame_v1 {
    uint64_t method_token;
    int64_t location;
} mw_live_frame_v1;

typedef struct __attribute__((aligned(8))) mw_live_snapshot_v1 {
    uint32_t abi_version, struct_bytes, status, reason;
    uint64_t process_cookie, process_start_ticks, installation_id, worker_id;
    uint64_t task_id, code_generation, invocation_id, parent_invocation_id, publication_id;
    uint32_t tgid, tid, binding, frame_count;
    mw_live_frame_v1 frames[MW_LIVE_MAX_FRAMES];
} mw_live_snapshot_v1;

typedef struct __attribute__((aligned(8))) mw_live_receipt_v1 {
    uint32_t abi_version, struct_bytes, status, reason;
    uint64_t process_cookie, process_start_ticks, installation_id, worker_id;
    uint64_t task_id, code_generation, invocation_id;
    uint32_t tgid, tid, worker_capacity, allocated_slots;
} mw_live_receipt_v1;

typedef struct __attribute__((aligned(8))) mw_live_info_v1 {
    uint32_t abi_version, struct_bytes, status, reason;
    uint64_t process_cookie, process_start_ticks;
    uint32_t tgid, arch, max_workers, max_classes, max_methods, max_frames;
    uint32_t binding_mask, reserved;
} mw_live_info_v1;

#ifdef __cplusplus
extern "C" {
#endif
__attribute__((visibility("default"))) uint32_t mazewall_live_info_v1(void *out, uint32_t out_bytes);
__attribute__((visibility("default"))) uint32_t mazewall_live_lifecycle_v1(
    const void *request, uint32_t request_bytes, void *receipt, uint32_t receipt_bytes);
__attribute__((visibility("default"))) uint32_t mazewall_live_copy_v1(
    const void *request, uint32_t request_bytes, void *snapshot, uint32_t snapshot_bytes);
#ifdef __cplusplus
}
#endif
#endif
