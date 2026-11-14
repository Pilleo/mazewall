#include "live_stack_context.hpp"
#include "live_stack_match_abi.h"

#include <atomic>
#include <cstddef>
#include <cstring>
#include <sys/syscall.h>
#include <unistd.h>

namespace mazewall::live {
namespace {
static_assert(sizeof(void*) == 8 && std::atomic<uint64_t>::is_always_lock_free);
static_assert(std::atomic<uint32_t>::is_always_lock_free && std::atomic<bool>::is_always_lock_free &&
              std::atomic<Reason>::is_always_lock_free);
#define RECORD(T, S) static_assert(sizeof(T) == S && alignof(T) == 8)
#define FIELD(T, F, O) static_assert(offsetof(T, F) == O)
RECORD(mw_live_match_request_v1, 128); RECORD(mw_live_match_result_v1, 16);
FIELD(mw_live_match_request_v1, request, 0); FIELD(mw_live_match_request_v1, expected_publication, 88);
FIELD(mw_live_match_request_v1, kind, 96); FIELD(mw_live_match_request_v1, flags_bits, 100);
FIELD(mw_live_match_request_v1, mode_bits, 104); FIELD(mw_live_match_request_v1, dirfd_bits, 108);
FIELD(mw_live_match_request_v1, path_bits, 112); FIELD(mw_live_match_request_v1, reserved, 120);
FIELD(mw_live_match_result_v1, abi_version, 0); FIELD(mw_live_match_result_v1, struct_bytes, 4);
FIELD(mw_live_match_result_v1, status, 8); FIELD(mw_live_match_result_v1, reserved, 12);
RECORD(mw_live_request_v1, 88); RECORD(mw_live_receipt_v1, 88);
RECORD(mw_live_frame_v1, 16); RECORD(mw_live_snapshot_v1, 2152); RECORD(mw_live_info_v1, 64);
#define COMMON(T) FIELD(T, abi_version, 0); FIELD(T, struct_bytes, 4); \
    FIELD(T, process_cookie, 16); FIELD(T, process_start_ticks, 24)
#define IDENTITIES(T) COMMON(T); FIELD(T, installation_id, 32); FIELD(T, worker_id, 40); \
    FIELD(T, task_id, 48); FIELD(T, code_generation, 56); FIELD(T, invocation_id, 64)
IDENTITIES(mw_live_request_v1); FIELD(mw_live_request_v1, operation, 8);
FIELD(mw_live_request_v1, reserved0, 12); FIELD(mw_live_request_v1, tgid, 72);
FIELD(mw_live_request_v1, tid, 76); FIELD(mw_live_request_v1, worker_capacity, 80);
FIELD(mw_live_request_v1, reserved1, 84);
IDENTITIES(mw_live_receipt_v1); FIELD(mw_live_receipt_v1, status, 8);
FIELD(mw_live_receipt_v1, reason, 12); FIELD(mw_live_receipt_v1, tgid, 72);
FIELD(mw_live_receipt_v1, tid, 76); FIELD(mw_live_receipt_v1, worker_capacity, 80);
FIELD(mw_live_receipt_v1, allocated_slots, 84);
IDENTITIES(mw_live_snapshot_v1); FIELD(mw_live_snapshot_v1, status, 8);
FIELD(mw_live_snapshot_v1, reason, 12); FIELD(mw_live_snapshot_v1, parent_invocation_id, 72);
FIELD(mw_live_snapshot_v1, publication_id, 80); FIELD(mw_live_snapshot_v1, tgid, 88);
FIELD(mw_live_snapshot_v1, tid, 92); FIELD(mw_live_snapshot_v1, binding, 96);
FIELD(mw_live_snapshot_v1, frame_count, 100); FIELD(mw_live_snapshot_v1, frames, 104);
FIELD(mw_live_frame_v1, method_token, 0); FIELD(mw_live_frame_v1, location, 8);
COMMON(mw_live_info_v1); FIELD(mw_live_info_v1, status, 8); FIELD(mw_live_info_v1, reason, 12);
FIELD(mw_live_info_v1, tgid, 32); FIELD(mw_live_info_v1, arch, 36);
FIELD(mw_live_info_v1, max_workers, 40); FIELD(mw_live_info_v1, max_classes, 44);
FIELD(mw_live_info_v1, max_methods, 48); FIELD(mw_live_info_v1, max_frames, 52);
FIELD(mw_live_info_v1, binding_mask, 56); FIELD(mw_live_info_v1, reserved, 60);
#undef IDENTITIES
#undef COMMON
#undef FIELD
#undef RECORD

constexpr uint32_t cells = MW_LIVE_MAX_WORKERS;
constexpr Outcome ok{Status::Ok, Reason::None}, busy{Status::Busy, Reason::SlotContended},
    stale{Status::Stale, Reason::IdentityMismatch}, revoked{Status::Stale, Reason::InstallationRevoked},
    generation_invalid{Status::Stale, Reason::GenerationInvalid},
    stopped{Status::Stale, Reason::Shutdown}, exhausted{Status::Exhausted, Reason::IdExhausted},
    capacity{Status::Exhausted, Reason::Capacity}, malformed{Status::InvalidArgument, Reason::Malformed};
enum class InstallationPhase : uint64_t { Staging = 1, Sealed, Invalid, Retired };
enum class WorkerPhase : uint64_t { Registered = 1, Exited, Retired };
template<class Phase> constexpr uint64_t word(uint64_t id, Phase phase) noexcept {
    return (id << 3) | static_cast<uint64_t>(phase);
}
uint64_t id_of(uint64_t control) noexcept { return control >> 3; }
uint64_t phase_of(uint64_t control) noexcept { return control & 7; }
struct Gate {
    std::atomic_flag held = ATOMIC_FLAG_INIT;
    bool acquire() noexcept { return !held.test_and_set(std::memory_order_acquire); }
    void release() noexcept { held.clear(std::memory_order_release); }
};
struct Lock {
    Gate& gate;
    bool locked;
    explicit Lock(Gate& g) noexcept : gate(g), locked(g.acquire()) {}
    ~Lock() { if (locked) gate.release(); }
};
struct Installation {
    std::atomic<uint64_t> control{0};
    Gate gate;
    uint32_t slots[cells]{}, count = 0, workers = 0;
    uint64_t generation = 0, serial = 0;
    uint64_t methods[MW_LIVE_MAX_METHODS]{};
    uint32_t method_count = 0;
    void* owner = nullptr;
    bool (*cleanup)(void*) noexcept = nullptr;
};
struct Worker {
    std::atomic<uint64_t> control{0}, task{0}, publication{0};
    std::atomic<Reason> unavailable{Reason::NoActiveInvocation};
    Gate gate;
    mw_live_request_v1 registration{};
    uint32_t installation_index = cells;
    uint64_t serial = 0, last_ended = 0;
    mw_live_snapshot_v1 payload{};
    Arguments arguments{};
};
// Immortal cells and authority: no destructor, reclamation or pointer routing on Copy.
Installation installations[cells];
Worker workers[cells];
Gate reservations;
uint64_t reserved_by[cells]{}; // reservations gate only
std::atomic<uint64_t> cookie{0}, start_ticks{0}, serial{0}, last_id{0};
std::atomic<uint32_t> tgid{0}, binding_mask{0};
std::atomic<bool> initialized{false}, shutdown_latch{false}, exhaustion{false};
std::atomic<Reason> readiness{Reason::RetransformUnavailable};
enum class TaskPhase { None, Active, Ended };
struct Current {
    bool platform = false;
    uint32_t wi = cells, ii = cells, depth = 0;
    bool poisoned = false;
    mw_live_request_v1 identity{};
    uint64_t task = 0, enrolled_serial = 0;
    void* enrollment_owner = nullptr;
    TaskPhase phase = TaskPhase::None;
};
thread_local Current current;
#ifdef MAZEWALL_LIVE_TEST
std::atomic<void (*)(TestPoint)> hook{nullptr};
void point(TestPoint p) { if (auto f = hook.load()) f(p); }
#define POINT(P) point(TestPoint::P)
#else
#define POINT(P) ((void)0)
#endif

bool semantic_id(uint64_t id) noexcept { return id && id <= MW_LIVE_MAX_ID; }
uint32_t current_tid() noexcept {
    const auto tid = syscall(SYS_gettid);
    return tid > 0 && static_cast<uint64_t>(tid) <= UINT32_MAX ? static_cast<uint32_t>(tid) : 0;
}
Outcome global() noexcept {
    if (shutdown_latch.load()) return stopped;
    if (exhaustion.load()) return exhausted;
    const auto reason = readiness.load();
    if (reason != Reason::None) return {Status::Unsupported, reason};
    return ok;
}
Outcome process(const mw_live_request_v1& q) noexcept {
    if (q.process_cookie != cookie.load() || q.process_start_ticks != start_ticks.load() ||
        q.tgid != tgid.load() || q.tgid != static_cast<uint32_t>(getpid())) return stale;
    return ok;
}
uint32_t find_installation(uint64_t id) noexcept {
    for (uint32_t i = 0; i < cells; ++i) if (id_of(installations[i].control.load()) == id) return i;
    return cells;
}
uint32_t find_worker(uint64_t id) noexcept {
    for (uint32_t i = 0; i < cells; ++i) if (id_of(workers[i].control.load()) == id) return i;
    return cells;
}
Outcome live(uint32_t ii, uint64_t id, uint64_t enrolled_serial) noexcept {
    auto result = global();
    if (!result.ok()) return result;
    if (serial.load() != enrolled_serial) return generation_invalid;
    auto control = installations[ii].control.load();
    if (id_of(control) != id) return stale;
    if (control != word(id, InstallationPhase::Sealed)) return revoked;
    return ok;
}
bool same_worker(const mw_live_request_v1& a, const mw_live_request_v1& b) noexcept {
    return a.process_cookie == b.process_cookie && a.process_start_ticks == b.process_start_ticks &&
        a.tgid == b.tgid && a.tid == b.tid && a.installation_id == b.installation_id &&
        a.worker_id == b.worker_id && a.code_generation == b.code_generation;
}
bool current_owner(const mw_live_request_v1& q) noexcept {
    return current.platform && current.wi < cells && current.ii < cells &&
        q.tid == current_tid() && same_worker(q, current.identity) && process(q).ok();
}
bool aligned(const void* p) noexcept { return p && (reinterpret_cast<uintptr_t>(p) & 7) == 0; }
template<class T> bool buffer(const void* p, uint32_t bytes) noexcept {
    return aligned(p) && bytes == sizeof(T);
}
Outcome validate(const void* input, uint32_t bytes, mw_live_request_v1& q, bool copy) noexcept {
    if (!buffer<mw_live_request_v1>(input, bytes)) return malformed;
    std::memcpy(&q, input, sizeof(q));
    if (q.abi_version != 1 || q.struct_bytes != sizeof(q)) return {Status::Unsupported, Reason::AbiMismatch};
    if (q.reserved0 || q.reserved1 || (copy ? q.operation != 0 : q.operation < 1 || q.operation > 8)) return malformed;
    // Bit positions are I,W,T,G,V,D,C; absent fields must be exactly zero.
    constexpr uint32_t required[] = {1|2|4|8|32, 64, 1|8, 1|8|32, 1|2|8|32,
                                    1|2|4|8|32, 1, 1|2|8|32, 1};
    const uint64_t values[] = {q.installation_id, q.worker_id, q.task_id, q.code_generation,
                               q.invocation_id, q.tid, q.worker_capacity};
    const auto mask = required[q.operation];
    for (uint32_t i = 0; i < 7; ++i) {
        if (mask & (1u << i)) {
            if (!values[i] || (i < 5 && !semantic_id(values[i]))) return malformed;
        } else if (!(copy && i == 4) && values[i]) return malformed;
    }
    if (copy && q.invocation_id && !semantic_id(q.invocation_id)) return malformed;
    if (q.worker_capacity > cells) return malformed;
    return ok;
}
template<class T> uint32_t finish(T& out, Outcome result) noexcept {
    if (!result.ok()) std::memset(&out, 0, sizeof(out));
    out.abi_version = 1; out.struct_bytes = sizeof(out);
    out.status = static_cast<uint32_t>(result.status); out.reason = static_cast<uint32_t>(result.reason);
    return out.status;
}
void receipt_process(mw_live_receipt_v1& r, const mw_live_request_v1& q) noexcept {
    r.process_cookie = q.process_cookie; r.process_start_ticks = q.process_start_ticks; r.tgid = q.tgid;
}
void receipt_worker(mw_live_receipt_v1& r, const mw_live_request_v1& q) noexcept {
    receipt_process(r, q); r.installation_id = q.installation_id; r.worker_id = q.worker_id;
    r.code_generation = q.code_generation; r.tid = q.tid;
}
Outcome invalidate(uint64_t id) noexcept {
    auto ii = find_installation(id);
    if (ii == cells) return stale;
    auto& c = installations[ii].control;
    auto expected = c.load();
    POINT(InvalidateCAS);
    // The only possible intervening non-revoking phase advance is Staging -> Sealed.
    for (unsigned attempt = 0; attempt != 2; ++attempt) {
        if (id_of(expected) != id) return stale;
        if (phase_of(expected) == 3 || phase_of(expected) == 4) return ok;
        if (phase_of(expected) != 1 && phase_of(expected) != 2) return stale;
        if (c.compare_exchange_strong(expected, word(id, InstallationPhase::Invalid))) return ok;
    }
    return id_of(expected) == id && (phase_of(expected) == 3 || phase_of(expected) == 4) ? ok : stale;
}
Outcome reserve(const mw_live_request_v1& q, mw_live_receipt_v1& r) noexcept {
    auto result = global(); if (!result.ok()) return result;
    Lock all(reservations); if (!all.locked) return busy;
    uint32_t ii = cells, free = 0;
    for (uint32_t i = 0; i < cells; ++i) {
        auto c = installations[i].control.load();
        if (ii == cells && (!c || phase_of(c) == 4)) ii = i;
        if (!reserved_by[i]) ++free;
    }
    if (ii == cells || free < q.worker_capacity) return capacity;
    auto& install = installations[ii];
    Lock admin(install.gate); if (!admin.locked) return busy;
    uint64_t id = 0; result = mint_id(id); if (!result.ok()) return result;
    install.count = q.worker_capacity; install.workers = 0; install.generation = 0;
    install.serial = serial.load(); install.method_count = 0; install.owner = nullptr; install.cleanup = nullptr;
    for (uint32_t i = 0, n = 0; i < cells && n < install.count; ++i) {
        if (!reserved_by[i]) { reserved_by[i] = id; install.slots[n++] = i; }
    }
    install.control.store(word(id, InstallationPhase::Staging));
    receipt_process(r, q); r.installation_id = id; r.worker_capacity = r.allocated_slots = install.count;
    return ok;
}
Outcome seal(const mw_live_request_v1& q, mw_live_receipt_v1& r) noexcept {
    auto result = global(); if (!result.ok()) return result;
    auto ii = find_installation(q.installation_id); if (ii == cells) return stale;
    auto& i = installations[ii]; Lock admin(i.gate); if (!admin.locked) return busy;
    auto control = i.control.load();
    if (id_of(control) != q.installation_id) return stale;
    if (phase_of(control) == 3 || phase_of(control) == 4) return revoked;
    if (!i.generation || i.generation != q.code_generation) return stale;
    if (i.serial != serial.load()) return generation_invalid;
    if (phase_of(control) == 1) {
        POINT(SealCAS);
        if (!i.control.compare_exchange_strong(control, word(q.installation_id, InstallationPhase::Sealed))) return revoked;
    } else if (phase_of(control) != 2) return stale;
    result = live(ii, q.installation_id, i.serial); if (!result.ok()) return result;
    receipt_process(r, q); r.installation_id = q.installation_id; r.code_generation = i.generation;
    r.worker_capacity = r.allocated_slots = i.count;
    return ok;
}
Outcome register_worker(const mw_live_request_v1& q, mw_live_receipt_v1& r) noexcept {
    auto result = global(); if (!result.ok()) return result;
    if (!current.platform || current.wi != cells || q.tid != current_tid()) return stale;
    auto ii = find_installation(q.installation_id); if (ii == cells) return stale;
    POINT(RegisterDiscovered);
    auto& i = installations[ii]; Lock admin(i.gate); if (!admin.locked) return busy;
    if (id_of(i.control.load()) != q.installation_id) return stale;
    result = live(ii, q.installation_id, i.serial); if (!result.ok()) return result;
    if (i.generation != q.code_generation) return stale;
    POINT(RegisterOwned);
    for (uint32_t n = 0; n < i.count; ++n) {
        auto wi = i.slots[n]; auto& w = workers[wi]; auto control = w.control.load();
        if (control && phase_of(control) != 3) continue;
        uint64_t id = 0; result = mint_id(id); if (!result.ok()) return result;
        Lock lock(w.gate); if (!lock.locked) return busy;
        result = live(ii, q.installation_id, i.serial); if (!result.ok()) return result;
        POINT(RegisterCommit);
        // After this check, commit/account the incarnation even if op6 wins concurrently.
        // No failed registration may rewrite a previous incarnation's tombstone.
        w.arguments = {};
        w.registration = q; w.registration.worker_id = id; w.registration.operation = 0;
        w.installation_index = ii; w.serial = i.serial; w.last_ended = 0;
        w.task.store(0); w.publication.store(0); w.unavailable.store(Reason::NoActiveInvocation);
        ++i.workers;
        current.identity = w.registration; current.wi = wi; current.ii = ii;
        current.task = 0; current.phase = TaskPhase::None; current.depth = 0; current.poisoned = false;
        current.enrolled_serial = i.serial;
        current.enrollment_owner = i.owner;
        w.control.store(word(id, WorkerPhase::Registered));
        receipt_worker(r, w.registration); r.worker_capacity = r.allocated_slots = i.count;
        return ok;
    }
    return capacity;
}
Outcome task(const mw_live_request_v1& q, mw_live_receipt_v1& r) noexcept {
    const bool end = q.operation == 5;
    if (!current_owner(q)) return stale;
    auto& w = workers[current.wi];
    if (w.control.load() != word(q.worker_id, WorkerPhase::Registered) ||
        id_of(installations[current.ii].control.load()) != q.installation_id) return stale;
    // R1: exact TLS and immortal atomic prevalidation, before the first gate attempt.
    if (end && (current.phase == TaskPhase::None || current.task != q.task_id ||
        w.task.load() != (current.phase == TaskPhase::Active ? q.task_id : 0))) return stale;
    if (!end && current.phase == TaskPhase::Active) return stale;
    Lock lock(w.gate); if (!lock.locked) return busy;
    if (w.control.load() != word(q.worker_id, WorkerPhase::Registered) ||
        !same_worker(q, w.registration)) return stale;
    if (end) {
        if (current.phase == TaskPhase::Ended) {
            if (w.last_ended != q.task_id || w.task.load()) return stale;
        } else {
            if (w.task.load() != q.task_id) return stale;
            w.publication.store(0); w.arguments = {}; w.task.store(0); w.last_ended = q.task_id;
            current.phase = TaskPhase::Ended;
        }
        receipt_worker(r, q); r.task_id = q.task_id;
        return ok;
    }
    auto result = live(current.ii, q.installation_id, w.serial); if (!result.ok()) return result;
    if (w.task.load()) return stale;
    const uint64_t id = q.task_id; // Minted before the gate by lifecycle dispatch.
    w.publication.store(0); w.unavailable.store(Reason::NoActiveInvocation); w.task.store(id);
    POINT(BeginBound);
    result = live(current.ii, q.installation_id, w.serial);
    if (!result.ok()) { w.task.store(0); return result; }
    current.task = id; current.phase = TaskPhase::Active;
    receipt_worker(r, q); r.task_id = id;
    return ok;
}
Outcome retire_worker(const mw_live_request_v1& q, mw_live_receipt_v1& r) noexcept {
    auto ii = find_installation(q.installation_id), wi = find_worker(q.worker_id);
    if (ii == cells || wi == cells) return stale;
    auto& i = installations[ii]; Lock admin(i.gate); if (!admin.locked) return busy;
    if (id_of(i.control.load()) != q.installation_id) return stale;
    auto& w = workers[wi]; Lock lock(w.gate); if (!lock.locked) return busy;
    const auto control = w.control.load();
    if (id_of(control) != q.worker_id || !same_worker(q, w.registration) || w.installation_index != ii) return stale;
    if (control == word(q.worker_id, WorkerPhase::Registered)) return {Status::Busy, Reason::NotPhysicallyRetired};
    if (control == word(q.worker_id, WorkerPhase::Exited)) {
        w.publication.store(0); w.arguments = {}; w.task.store(0); --i.workers;
        w.control.store(word(q.worker_id, WorkerPhase::Retired));
    } else if (control != word(q.worker_id, WorkerPhase::Retired)) return stale;
    receipt_worker(r, q); r.worker_capacity = r.allocated_slots = i.count;
    return ok;
}
Outcome retire_installation(const mw_live_request_v1& q, mw_live_receipt_v1& r) noexcept {
    auto result = invalidate(q.installation_id); if (!result.ok()) return result;
    auto ii = find_installation(q.installation_id); if (ii == cells) return stale;
    auto& i = installations[ii]; Lock admin(i.gate);
    if (!admin.locked) return busy;
    auto control = i.control.load(); if (id_of(control) != q.installation_id) return stale;
    if (phase_of(control) != 4) {
        if (i.workers) return {Status::Busy, Reason::CleanupPending};
        Lock all(reservations); if (!all.locked) return busy;
        if (i.cleanup && !i.cleanup(i.owner)) return {Status::Busy, Reason::CleanupPending};
        i.owner = nullptr; i.cleanup = nullptr; i.method_count = 0; i.generation = 0;
        for (uint32_t n = 0; n < i.count; ++n) reserved_by[i.slots[n]] = 0;
        i.count = 0; i.control.store(word(q.installation_id, InstallationPhase::Retired));
    }
    receipt_process(r, q); r.installation_id = q.installation_id;
    return ok;
}
Outcome lifecycle(const mw_live_request_v1& q, mw_live_receipt_v1& r) noexcept {
    if (q.operation <= 3 && readiness.load() != Reason::None) return global();
    auto result = process(q); if (!result.ok()) return result;
    switch (q.operation) {
        case 1: return reserve(q, r);
        case 2: return seal(q, r);
        case 3: return register_worker(q, r);
        case 4: {
            if (!current_owner(q) || current.phase == TaskPhase::Active) return stale;
            result = global(); if (!result.ok()) return result;
            uint64_t id = 0; result = mint_id(id); if (!result.ok()) return result;
            auto with_task = q; with_task.task_id = id;
            return task(with_task, r);
        }
        case 5: return task(q, r);
        case 6:
            result = invalidate(q.installation_id);
            if (result.ok()) { receipt_process(r, q); r.installation_id = q.installation_id; }
            return result;
        case 7: return retire_worker(q, r);
        case 8: return retire_installation(q, r);
        default: return malformed;
    }
}
Outcome copy(const mw_live_request_v1& q, mw_live_snapshot_v1& out) noexcept {
    if (readiness.load() != Reason::None) return global();
    auto result = process(q); if (!result.ok()) return result;
    result = global(); if (!result.ok()) return result;
    auto wi = find_worker(q.worker_id); if (wi == cells) return stale;
    auto& w = workers[wi];
    if (w.control.load() != word(q.worker_id, WorkerPhase::Registered)) return stale;
    POINT(CopyDiscovered);
    if (!w.gate.acquire()) return busy;
    if (w.control.load() != word(q.worker_id, WorkerPhase::Registered)) { w.gate.release(); return stale; }
    POINT(CopyLocked);
    if (!same_worker(q, w.registration) || w.installation_index >= cells) { w.gate.release(); return stale; }
    const uint32_t ii = w.installation_index;
    const uint64_t enrolled_serial = w.serial;
    result = live(ii, q.installation_id, enrolled_serial);
    if (!result.ok()) { w.gate.release(); return result; }
    const auto task_id = w.task.load(), publication_id = w.publication.load();
    if (!task_id) { w.gate.release(); return {Status::Absent, Reason::NoTask}; }
    if (task_id != q.task_id) { w.gate.release(); return stale; }
    if (!publication_id) {
        auto reason = w.unavailable.load(); w.gate.release();
        if (reason == Reason::CaptureFailed || reason == Reason::Overflow || reason == Reason::UnknownMethod ||
            reason == Reason::NestedOrCallback) return {Status::Incomplete, reason};
        return {Status::Absent, Reason::NoActiveInvocation};
    }
    out = w.payload;
    if (out.worker_id != q.worker_id || out.task_id != task_id || out.installation_id != q.installation_id ||
        out.code_generation != q.code_generation || out.process_cookie != q.process_cookie ||
        out.process_start_ticks != q.process_start_ticks || out.tgid != q.tgid || out.tid != q.tid ||
        !semantic_id(out.invocation_id) || out.publication_id != publication_id || out.parent_invocation_id ||
        out.frame_count < 1 || out.frame_count > MW_LIVE_MAX_FRAMES || out.binding < 1 || out.binding > 2 ||
        (q.invocation_id && out.invocation_id != q.invocation_id)) {
        w.gate.release(); return stale;
    }
    for (uint32_t n = 0; n < out.frame_count; ++n) {
        if (!semantic_id(out.frames[n].method_token)) { w.gate.release(); return stale; }
    }
    result = live(ii, q.installation_id, enrolled_serial);
    w.gate.release();
    POINT(CopyReleased);
    if (!result.ok()) return result;
    // After unlock: local scalars and immortal atomics ONLY, even if both cells were reused.
    result = live(ii, q.installation_id, enrolled_serial); if (!result.ok()) return result;
    if (w.control.load() != word(q.worker_id, WorkerPhase::Registered) ||
        w.task.load() != task_id || w.publication.load() != publication_id) return stale;
    return ok;
}
} // namespace

bool initialize(const Startup& s) noexcept {
    bool expected = false;
    if (!initialized.compare_exchange_strong(expected, true)) return false;
    if (!s.hooks_ready) return false;
#if !defined(__x86_64__)
    readiness.store(Reason::RuntimeMismatch); return false;
#endif
    const auto pid = getpid();
    if (!s.runtime_verified || !s.cookie || !s.start_ticks || pid <= 0 || s.tgid != static_cast<uint32_t>(pid)) {
        readiness.store(Reason::RuntimeMismatch); return false;
    }
    if (!s.helper_registered) return false;
    if (!s.binding_mask || (s.binding_mask & ~3u)) { readiness.store(Reason::MissingBinding); return false; }
    cookie.store(s.cookie); start_ticks.store(s.start_ticks); tgid.store(s.tgid); binding_mask.store(s.binding_mask);
    readiness.store(Reason::None);
    return true;
}
Outcome mint_id(uint64_t& id) noexcept {
    id = 0;
    if (exhaustion.load()) return exhausted;
    auto previous = last_id.load();
    if (previous >= MW_LIVE_MAX_ID) { exhaustion.store(true); return exhausted; }
    if (!last_id.compare_exchange_strong(previous, previous + 1)) return busy;
    id = previous + 1; return ok;
}
uint64_t replacement_serial() noexcept { return serial.load(); }
void replacement() noexcept {
    auto old = serial.load();
    // Hooks must advance, not lose an invalidation on contention. Saturating CAS outside Copy/gates.
    while (old < MW_LIVE_MAX_ID) {
        if (serial.compare_exchange_strong(old, old + 1)) return;
    }
    exhaustion.store(true);
}
void shutdown() noexcept {
    shutdown_latch.store(true);
    for (auto& i : installations) {
        auto c = i.control.load();
        if (c && (phase_of(c) == 1 || phase_of(c) == 2)) (void)invalidate(id_of(c));
    }
}
Enrollment::Enrollment(uint64_t installation) noexcept : installation_(installation) {
    outcome_ = global(); if (!outcome_.ok()) return;
    auto ii = semantic_id(installation) ? find_installation(installation) : cells;
    if (ii == cells) { outcome_ = stale; return; }
    auto& i = installations[ii];
    if (!i.gate.acquire()) { outcome_ = busy; return; }
    index_ = ii;
    if (i.control.load() != word(installation, InstallationPhase::Staging) || i.generation) {
        outcome_ = stale; return;
    }
    serial_ = replacement_serial();
    if (serial_ != i.serial) { outcome_ = generation_invalid; return; }
    outcome_ = ok;
}
Enrollment::~Enrollment() { if (index_ < cells) installations[index_].gate.release(); }
Outcome Enrollment::commit(void* owner, bool (*cleanup)(void*) noexcept, uint32_t classes,
                            const uint64_t* tokens, uint32_t methods, uint64_t& generation) noexcept {
    generation = 0;
    if (!outcome_.ok()) return outcome_;
    if (!cleanup || !classes || classes > MW_LIVE_MAX_CLASSES || !tokens || !methods || methods > MW_LIVE_MAX_METHODS)
        return malformed;
    auto& i = installations[index_];
    if (i.generation) return stale;
    for (uint32_t n = 0; n < methods; ++n) {
        if (!semantic_id(tokens[n]) || tokens[n] > last_id.load()) return malformed;
        for (uint32_t m = 0; m < n; ++m) if (tokens[m] == tokens[n]) return malformed;
    }
    auto result = global(); if (!result.ok()) return result;
    if (replacement_serial() != serial_) return generation_invalid;
    if (i.control.load() != word(installation_, InstallationPhase::Staging)) return revoked;
    uint64_t id = 0; result = mint_id(id); if (!result.ok()) return result;
    std::memcpy(i.methods, tokens, methods * sizeof(uint64_t));
    i.method_count = methods; i.owner = owner; i.cleanup = cleanup; i.serial = serial_; i.generation = id;
    generation = id;
    return ok;
}
void current_platform_thread() noexcept { if (current.wi == cells) current.platform = true; }
void current_thread_exited() noexcept {
    if (current.wi < cells) {
        auto& w = workers[current.wi]; w.publication.store(0);
        auto expected = word(current.identity.worker_id, WorkerPhase::Registered);
        (void)w.control.compare_exchange_strong(expected, word(current.identity.worker_id, WorkerPhase::Exited));
    }
    current = {};
}
void* current_enrollment_owner() noexcept {
    if (current.wi >= cells || current.ii >= cells || !current_owner(current.identity) ||
        workers[current.wi].control.load() != word(current.identity.worker_id, WorkerPhase::Registered) ||
        !live(current.ii, current.identity.installation_id, current.enrolled_serial).ok()) return nullptr;
    return current.enrollment_owner;
}
void enter() noexcept {
    if (current.wi < cells) {
        auto& w = workers[current.wi]; w.publication.store(0); w.unavailable.store(Reason::NoActiveInvocation);
        if (current.depth) { current.poisoned = true; w.unavailable.store(Reason::NestedOrCallback); }
    }
    if (current.depth == UINT32_MAX) { current.poisoned = true; return; }
    ++current.depth;
}
bool probe_eligible() noexcept {
    if (!current.platform || current.wi >= cells || current.ii >= cells ||
        current.phase != TaskPhase::Active || !current.task || current.depth != 1 || current.poisoned)
        return false;
    const auto& q = current.identity;
    return q.process_cookie == cookie.load() && q.process_start_ticks == start_ticks.load() &&
        q.tgid == tgid.load() && workers[current.wi].task.load() == current.task &&
        workers[current.wi].control.load() == word(q.worker_id, WorkerPhase::Registered) &&
        installations[current.ii].control.load() == word(q.installation_id, InstallationPhase::Sealed) &&
        serial.load() == current.enrolled_serial && readiness.load() == Reason::None &&
        !shutdown_latch.load() && !exhaustion.load();
}
void poison() noexcept {
    current.poisoned = true;
    if (current.wi < cells) { workers[current.wi].publication.store(0); workers[current.wi].unavailable.store(Reason::NestedOrCallback); }
}
void leave() noexcept {
    if (current.wi < cells) workers[current.wi].publication.store(0);
    if (current.depth) --current.depth;
    if (!current.depth) current.poisoned = false;
}
void publish(uint32_t binding, const mw_live_frame_v1* frames, uint32_t count, Reason failure, const Arguments& arguments) noexcept {
    if (current.wi == cells || current.phase != TaskPhase::Active || !current.depth) return;
    auto& w = workers[current.wi]; w.publication.store(0);
    if (current.poisoned || current.depth != 1) { poison(); return; }
    if (!live(current.ii, current.identity.installation_id, current.enrolled_serial).ok()) return;
    if (failure == Reason::None) {
        if (count > MW_LIVE_MAX_FRAMES) failure = Reason::Overflow;
        else if (!frames || !count || binding < 1 || binding > 2 || !(binding_mask.load() & (1u << (binding - 1))))
            failure = Reason::CaptureFailed;
    }
    if (failure == Reason::None) {
        // Tables remain owned through this worker's physical exit; Copy never reads them.
        const auto& i = installations[current.ii];
        for (uint32_t n = 0; n < count; ++n) {
            bool found = false;
            for (uint32_t m = 0; m < i.method_count; ++m) if (frames[n].method_token == i.methods[m]) { found = true; break; }
            if (!found) { failure = Reason::UnknownMethod; break; }
        }
    }
    if (failure != Reason::None) {
        if (failure != Reason::Overflow && failure != Reason::UnknownMethod && failure != Reason::NestedOrCallback)
            failure = Reason::CaptureFailed;
        w.unavailable.store(failure); return;
    }
    uint64_t invocation = 0, publication = 0;
    if (!mint_id(invocation).ok() || !mint_id(publication).ok()) { w.unavailable.store(Reason::CaptureFailed); return; }
    mw_live_snapshot_v1 payload{};
    const auto& q = current.identity;
    payload.process_cookie = q.process_cookie; payload.process_start_ticks = q.process_start_ticks;
    payload.installation_id = q.installation_id; payload.worker_id = q.worker_id;
    payload.task_id = current.task; payload.code_generation = q.code_generation;
    payload.invocation_id = invocation; payload.publication_id = publication;
    payload.tgid = q.tgid; payload.tid = q.tid; payload.binding = binding; payload.frame_count = count;
    std::memcpy(payload.frames, frames, count * sizeof(*frames));
    Lock lock(w.gate);
    if (!lock.locked) { w.unavailable.store(Reason::NoActiveInvocation); return; }
    POINT(PublishLocked);
    if (w.control.load() != word(q.worker_id, WorkerPhase::Registered) || w.task.load() != current.task ||
        !same_worker(q, w.registration) || !live(current.ii, q.installation_id, w.serial).ok()) return;
    const bool valid_arguments = (binding == 1 && arguments.kind == ArgumentKind::Open0 && arguments.dirfd_bits == 0) ||
        (binding == 2 && arguments.kind == ArgumentKind::Openat0);
    w.payload = payload; w.arguments = valid_arguments ? arguments : Arguments{}; w.publication.store(publication);
}

MatchResult match_arguments(const mw_live_request_v1& input, uint64_t expected_publication, const Arguments& candidate) noexcept {
    mw_live_request_v1 q{};
    if (!validate(&input, sizeof(input), q, true).ok() || !semantic_id(q.invocation_id) ||
        !semantic_id(expected_publication)) return MatchResult::Unavailable;
    const bool valid = (candidate.kind == ArgumentKind::Open0 && candidate.dirfd_bits == 0) ||
        candidate.kind == ArgumentKind::Openat0;
    if (!valid || !process(q).ok() || !global().ok()) return MatchResult::Unavailable;
    const auto wi = find_worker(q.worker_id); if (wi == cells) return MatchResult::Unavailable;
    auto& w = workers[wi];
    if (w.control.load() != word(q.worker_id, WorkerPhase::Registered) || !w.gate.acquire()) return MatchResult::Unavailable;
    bool matched = false;
    uint32_t ii = cells; uint64_t enrolled_serial = 0, task_id = 0, publication_id = 0;
    if (w.control.load() == word(q.worker_id, WorkerPhase::Registered) && same_worker(q, w.registration) && w.installation_index < cells) {
        ii = w.installation_index; enrolled_serial = w.serial;
        task_id = w.task.load(); publication_id = w.publication.load();
        const auto& out = w.payload; const auto& a = w.arguments;
        matched = live(ii, q.installation_id, enrolled_serial).ok() && task_id == q.task_id && publication_id == expected_publication &&
            out.worker_id == q.worker_id && out.task_id == task_id && out.installation_id == q.installation_id &&
            out.code_generation == q.code_generation && out.process_cookie == q.process_cookie &&
            out.process_start_ticks == q.process_start_ticks && out.tgid == q.tgid && out.tid == q.tid &&
            out.invocation_id == q.invocation_id && out.publication_id == publication_id && !out.parent_invocation_id &&
            out.frame_count >= 1 && out.frame_count <= MW_LIVE_MAX_FRAMES &&
            ((out.binding == 1 && a.kind == ArgumentKind::Open0) || (out.binding == 2 && a.kind == ArgumentKind::Openat0)) &&
            a.kind == candidate.kind && a.path_bits == candidate.path_bits && a.flags_bits == candidate.flags_bits &&
            a.mode_bits == candidate.mode_bits && a.dirfd_bits == candidate.dirfd_bits;
        if (matched) for (uint32_t n = 0; n < out.frame_count; ++n)
            if (!semantic_id(out.frames[n].method_token)) { matched = false; break; }
        if (matched) matched = live(ii, q.installation_id, enrolled_serial).ok();
    }
    w.gate.release();
#ifdef MAZEWALL_LIVE_TEST
    if (matched) {
        POINT(MatchReleased);
        POINT(MatchLive); if (!live(ii, q.installation_id, enrolled_serial).ok()) return MatchResult::Unavailable;
        POINT(MatchControl); if (w.control.load() != word(q.worker_id, WorkerPhase::Registered)) return MatchResult::Unavailable;
        POINT(MatchTask); if (w.task.load() != task_id) return MatchResult::Unavailable;
        POINT(MatchPublication); if (w.publication.load() != publication_id) return MatchResult::Unavailable;
    }
#endif
    if (!matched || !live(ii, q.installation_id, enrolled_serial).ok() ||
        w.control.load() != word(q.worker_id, WorkerPhase::Registered) || w.task.load() != task_id ||
        w.publication.load() != publication_id) return MatchResult::Unavailable;
    return MatchResult::Match;
}

#ifdef MAZEWALL_LIVE_TEST
void test_hook(void (*f)(TestPoint)) noexcept { hook.store(f); }
void test_limits(uint64_t id, uint64_t replacement) noexcept { last_id.store(id); serial.store(replacement); }
bool test_gate(uint64_t id, bool acquire) noexcept {
    auto wi = find_worker(id); if (wi == cells) return false;
    if (acquire) return workers[wi].gate.acquire();
    workers[wi].gate.release(); return true;
}
void test_process(uint32_t pid) noexcept { tgid.store(pid); }
#endif
} // namespace mazewall::live

extern "C" uint32_t mazewall_live_info_v1(void* output, uint32_t bytes) {
    using namespace mazewall::live;
    if (!buffer<mw_live_info_v1>(output, bytes)) return static_cast<uint32_t>(Status::InvalidArgument);
    mw_live_info_v1 out{};
    auto result = global();
    if (result.ok()) {
        out.process_cookie = cookie.load(); out.process_start_ticks = start_ticks.load(); out.tgid = tgid.load();
        out.arch = 1; out.max_workers = cells; out.max_classes = MW_LIVE_MAX_CLASSES;
        out.max_methods = MW_LIVE_MAX_METHODS; out.max_frames = MW_LIVE_MAX_FRAMES; out.binding_mask = binding_mask.load();
    }
    auto status = finish(out, result); std::memcpy(output, &out, sizeof(out)); return status;
}
extern "C" uint32_t mazewall_live_lifecycle_v1(const void* input, uint32_t input_bytes,
                                               void* output, uint32_t output_bytes) {
    using namespace mazewall::live;
    if (!buffer<mw_live_receipt_v1>(output, output_bytes)) return static_cast<uint32_t>(Status::InvalidArgument);
    mw_live_request_v1 q{}; mw_live_receipt_v1 out{};
    auto result = validate(input, input_bytes, q, false);
    if (result.ok()) result = lifecycle(q, out);
    auto status = finish(out, result); std::memcpy(output, &out, sizeof(out)); return status;
}
extern "C" uint32_t mazewall_live_copy_v1(const void* input, uint32_t input_bytes,
                                         void* output, uint32_t output_bytes) {
    using namespace mazewall::live;
    if (!buffer<mw_live_snapshot_v1>(output, output_bytes)) return static_cast<uint32_t>(Status::InvalidArgument);
    mw_live_request_v1 q{}; mw_live_snapshot_v1 out{};
    auto result = validate(input, input_bytes, q, true);
    if (result.ok()) result = copy(q, out);
    auto status = finish(out, result); std::memcpy(output, &out, sizeof(out)); return status;
}

extern "C" uint32_t mazewall_live_match_arguments_v1(const void* input, uint32_t input_bytes,
                                                      void* output, uint32_t output_bytes) {
    using namespace mazewall::live;
    bool safe_output = false;
    try {
        if (!output || (reinterpret_cast<uintptr_t>(output) & 7) || output_bytes != sizeof(mw_live_match_result_v1))
            return MW_LIVE_MATCH_MALFORMED;
        const auto in = reinterpret_cast<uintptr_t>(input), out = reinterpret_cast<uintptr_t>(output);
        if (input_bytes > UINTPTR_MAX - in || output_bytes > UINTPTR_MAX - out) return MW_LIVE_MATCH_MALFORMED;
        const auto in_end = in + input_bytes, out_end = out + output_bytes;
        if (input_bytes && in < out_end && out < in_end) return MW_LIVE_MATCH_MALFORMED;
        safe_output = true;
        mw_live_match_result_v1 result{1, sizeof(result), MW_LIVE_MATCH_MALFORMED, 0};
        if (input && !(in & 7) && input_bytes == sizeof(mw_live_match_request_v1)) {
            mw_live_match_request_v1 wire{}; std::memcpy(&wire, input, sizeof(wire));
            mw_live_request_v1 request{};
            const auto& q = wire.request;
            if (validate(&q, sizeof(q), request, true).ok() && semantic_id(q.invocation_id) &&
                semantic_id(wire.expected_publication) && q.process_cookie && q.process_start_ticks &&
                q.tgid && q.tgid <= INT32_MAX && q.tid && q.tid <= INT32_MAX && !wire.reserved &&
                ((wire.kind == 1 && !wire.dirfd_bits) || wire.kind == 2)) {
                const Arguments args{wire.kind == 1 ? ArgumentKind::Open0 : ArgumentKind::Openat0,
                    wire.path_bits, wire.flags_bits, wire.mode_bits, wire.dirfd_bits};
                result.status = match_arguments(request, wire.expected_publication, args) == MatchResult::Match ?
                    MW_LIVE_MATCH_MATCH : MW_LIVE_MATCH_UNAVAILABLE;
            }
        }
        std::memcpy(output, &result, sizeof(result)); return result.status;
    } catch (...) {
        if (safe_output) {
            const mw_live_match_result_v1 result{1, sizeof(result), MW_LIVE_MATCH_UNAVAILABLE, 0};
            std::memcpy(output, &result, sizeof(result)); return result.status;
        }
        return MW_LIVE_MATCH_MALFORMED;
    }
}
