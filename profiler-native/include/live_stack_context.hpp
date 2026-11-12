#pragma once

#include "live_stack_context_abi.h"

namespace mazewall::live {

enum class __attribute__((visibility("hidden"))) Status : uint32_t {
    Ok = MW_LIVE_AVAILABLE, Absent = MW_LIVE_ABSENT, Incomplete = MW_LIVE_INCOMPLETE,
    Busy = MW_LIVE_BUSY, Stale = MW_LIVE_STALE, Unsupported = MW_LIVE_UNSUPPORTED,
    InvalidArgument = MW_LIVE_INVALID_ARGUMENT, Exhausted = MW_LIVE_EXHAUSTED,
    InternalFailure = MW_LIVE_INTERNAL_FAILURE
};
enum class __attribute__((visibility("hidden"))) Reason : uint32_t {
    None = MW_LIVE_NONE, NoTask = MW_LIVE_NO_TASK, NoActiveInvocation = MW_LIVE_NO_ACTIVE_INVOCATION,
    CaptureFailed = MW_LIVE_CAPTURE_FAILED, Overflow = MW_LIVE_OVERFLOW, UnknownMethod = MW_LIVE_UNKNOWN_METHOD,
    NestedOrCallback = MW_LIVE_NESTED_OR_CALLBACK, SlotContended = MW_LIVE_SLOT_CONTENDED,
    IdentityMismatch = MW_LIVE_IDENTITY_MISMATCH, GenerationInvalid = MW_LIVE_GENERATION_INVALID,
    Shutdown = MW_LIVE_SHUTDOWN, MissingBinding = MW_LIVE_MISSING_BINDING, RuntimeMismatch = MW_LIVE_RUNTIME_MISMATCH,
    AbiMismatch = MW_LIVE_ABI_MISMATCH, Capacity = MW_LIVE_CAPACITY, IdExhausted = MW_LIVE_ID_EXHAUSTED,
    Malformed = MW_LIVE_MALFORMED, RegistrationMissing = MW_LIVE_REGISTRATION_MISSING,
    NativeFailure = MW_LIVE_NATIVE_FAILURE, RetransformUnavailable = MW_LIVE_RETRANSFORM_UNAVAILABLE,
    InstallationRevoked = MW_LIVE_INSTALLATION_REVOKED, NotPhysicallyRetired = MW_LIVE_NOT_PHYSICALLY_RETIRED,
    CleanupPending = MW_LIVE_CLEANUP_PENDING
};
struct __attribute__((visibility("hidden"))) Outcome {
    Status status;
    Reason reason;
    bool ok() const noexcept { return status == Status::Ok; }
};

// Only the startup agent may supply these verified facts; library loading supplies none.
struct __attribute__((visibility("hidden"))) Startup {
    uint64_t cookie, start_ticks;
    uint32_t tgid, binding_mask;
    bool runtime_verified, hooks_ready, helper_registered;
};
__attribute__((visibility("hidden"))) bool initialize(const Startup&) noexcept;
__attribute__((visibility("hidden"))) uint64_t replacement_serial() noexcept;
__attribute__((visibility("hidden"))) void replacement() noexcept;
__attribute__((visibility("hidden"))) void shutdown() noexcept;
__attribute__((visibility("hidden"))) Outcome mint_id(uint64_t& id) noexcept;

// Holds the exact installation's administrative gate across actual JNI enrollment.
// A failed commit leaves ownership with the caller; cleanup retries retain the owner.
class __attribute__((visibility("hidden"))) Enrollment final {
public:
    explicit Enrollment(uint64_t installation_id) noexcept;
    ~Enrollment();
    Enrollment(const Enrollment&) = delete;
    Enrollment& operator=(const Enrollment&) = delete;
    Outcome outcome() const noexcept { return outcome_; }
    uint64_t serial() const noexcept { return serial_; }
    Outcome commit(void* owner, bool (*cleanup)(void*) noexcept, uint32_t classes,
                   const uint64_t* method_tokens, uint32_t methods, uint64_t& generation) noexcept;
private:
    uint32_t index_ = MW_LIVE_MAX_WORKERS;
    uint64_t installation_ = 0, serial_ = 0;
    Outcome outcome_{Status::Stale, Reason::IdentityMismatch};
};

// ThreadStart integration must first establish that the actual current JVM thread is platform.
__attribute__((visibility("hidden"))) void current_platform_thread() noexcept;
__attribute__((visibility("hidden"))) void current_thread_exited() noexcept;
// Actual worker only; the immutable JNI enrollment owner stays pinned until physical exit.
// Never retain this pointer beyond that worker or expose it to Copy/FFM.
__attribute__((visibility("hidden"))) void* current_enrollment_owner() noexcept;
// Entry clears before capture. Capture/translation occurs outside the payload gate.
__attribute__((visibility("hidden"))) void enter() noexcept;
// Momentary TLS/immortal-atomic prerequisite only; publish still rechecks authority.
__attribute__((visibility("hidden"))) bool probe_eligible() noexcept;
__attribute__((visibility("hidden"))) void publish(uint32_t binding, const mw_live_frame_v1* frames,
                                                uint32_t count, Reason failure = Reason::None) noexcept;
__attribute__((visibility("hidden"))) void poison() noexcept;
__attribute__((visibility("hidden"))) void leave() noexcept;

#ifdef MAZEWALL_LIVE_TEST
enum class TestPoint { CopyDiscovered, CopyLocked, CopyReleased, InvalidateCAS, SealCAS,
                       RegisterDiscovered, RegisterOwned, RegisterCommit, BeginBound, PublishLocked };
// Separately compiled test core only: no hooks or synthetic enrollment in the DSO.
void test_hook(void (*hook)(TestPoint)) noexcept;
void test_limits(uint64_t next_id, uint64_t serial) noexcept;
bool test_gate(uint64_t worker_id, bool acquire) noexcept;
void test_process(uint32_t tgid) noexcept;
#endif
} // namespace mazewall::live
