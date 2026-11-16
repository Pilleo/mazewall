#include "live_stack_context.hpp"
#include "live_stack_match_abi.h"

#include <cassert>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <dlfcn.h>
#include <elf.h>
#include <fstream>
#include <functional>
#include <cstdio>
#include <future>
#include <mutex>
#include <source_location>
#include <string>
#include <vector>
#include <thread>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/wait.h>

using namespace mazewall::live;
using namespace std::chrono_literals;

namespace {
struct Rendezvous {
    std::mutex mutex;
    std::condition_variable cv;
    bool ready = false, released = false;
    void pause() {
        std::unique_lock lock(mutex);
        ready = true;
        cv.notify_all();
        assert(cv.wait_for(lock, 10s, [&] { return released; }));
    }
    void wait() {
        std::unique_lock lock(mutex);
        assert(cv.wait_for(lock, 10s, [&] { return ready; }));
    }
    void release() { std::lock_guard lock(mutex); released = true; cv.notify_all(); }
};
mw_live_request_v1 request(uint32_t op) {
    mw_live_request_v1 q{};
    q.abi_version = 1; q.struct_bytes = 88; q.operation = op;
    q.process_cookie = 91; q.process_start_ticks = 37; q.tgid = getpid();
    return q;
}
mw_live_receipt_v1 call(const mw_live_request_v1& q, uint32_t status = 0, uint32_t reason = 0) {
    mw_live_receipt_v1 r;
    std::memset(&r, 0xAB, sizeof(r));
    auto actual = mazewall_live_lifecycle_v1(&q, 88, &r, 88);
    if (actual != status || r.reason != reason)
        std::fprintf(stderr, "op %u: got %u/%u expected %u/%u\n", q.operation, actual, r.reason, status, reason);
    assert(actual == status);
    assert(r.status == status && r.reason == reason);
    assert(r.abi_version == 1 && r.struct_bytes == 88);
    if (status) {
        const auto* p = reinterpret_cast<const unsigned char*>(&r);
        for (size_t i = 16; i < sizeof(r); ++i) assert(p[i] == 0);
    } else {
        mw_live_receipt_v1 expected{};
        expected.abi_version = 1; expected.struct_bytes = 88;
        expected.process_cookie = q.process_cookie; expected.process_start_ticks = q.process_start_ticks; expected.tgid = q.tgid;
        expected.installation_id = q.operation == 1 ? r.installation_id : q.installation_id;
        assert(expected.installation_id && expected.installation_id <= MW_LIVE_MAX_ID);
        if (q.operation == 2 || q.operation == 3 || q.operation == 4 || q.operation == 5 || q.operation == 7)
            expected.code_generation = q.code_generation;
        if (q.operation == 3 || q.operation == 4 || q.operation == 5 || q.operation == 7) {
            expected.worker_id = q.operation == 3 ? r.worker_id : q.worker_id;
            expected.tid = q.tid;
            assert(expected.worker_id && expected.worker_id <= MW_LIVE_MAX_ID);
        }
        if (q.operation == 4 || q.operation == 5) {
            expected.task_id = q.operation == 4 ? r.task_id : q.task_id;
            assert(expected.task_id && expected.task_id <= MW_LIVE_MAX_ID);
        }
        if (q.operation == 1 || q.operation == 2 || q.operation == 3 || q.operation == 7) {
            expected.worker_capacity = q.operation == 1 ? q.worker_capacity : r.worker_capacity;
            expected.allocated_slots = expected.worker_capacity;
            assert(expected.worker_capacity && expected.worker_capacity <= 256);
        }
        assert(std::memcmp(&r, &expected, 88) == 0);
    }
    return r;
}
mw_live_snapshot_v1 read(const mw_live_request_v1& q, uint32_t status = 0, uint32_t reason = 0,
                         std::source_location location = std::source_location::current()) {
    mw_live_snapshot_v1 s;
    std::memset(&s, 0xAB, sizeof(s));
    auto actual = mazewall_live_copy_v1(&q, 88, &s, 2152);
    if (actual != status || s.reason != reason)
        std::fprintf(stderr, "copy line %u: got %u/%u expected %u/%u\n", location.line(), actual, s.reason, status, reason);
    assert(actual == status && s.status == status && s.reason == reason);
    assert(s.abi_version == 1 && s.struct_bytes == 2152);
    const auto* p = reinterpret_cast<const unsigned char*>(&s);
    if (status) for (size_t i = 16; i < sizeof(s); ++i) assert(p[i] == 0);
    else for (size_t i = 104 + 16 * s.frame_count; i < sizeof(s); ++i) assert(p[i] == 0);
    return s;
}
template<class T> T completed(std::future<T>& f) {
    assert(f.wait_for(10s) == std::future_status::ready);
    return f.get();
}

// Commands execute on the actual native worker, not a caller-supplied TID substitute.
class WorkerThread {
    std::mutex mutex;
    std::condition_variable cv;
    std::function<void()> command;
    bool stop = false;
    std::promise<void> exited;
    std::future<void> done = exited.get_future();
    std::thread thread{[this] {
        current_platform_thread();
        for (;;) {
            std::unique_lock lock(mutex);
            assert(cv.wait_for(lock, 10s, [&] { return stop || static_cast<bool>(command); }));
            if (stop) break;
            auto work = std::move(command); command = {};
            lock.unlock(); work();
        }
        current_thread_exited(); exited.set_value();
    }};
public:
    template<class F> auto async(F f) {
        using T = decltype(f());
        auto task = std::make_shared<std::packaged_task<T()>>(std::move(f));
        auto future = task->get_future();
        std::lock_guard lock(mutex); assert(!command && !stop);
        command = [task] { (*task)(); }; cv.notify_all();
        return future;
    }
    template<class F> auto run(F f) { auto future = async(std::move(f)); return completed(future); }
    void join() {
        { std::lock_guard lock(mutex); stop = true; cv.notify_all(); }
        completed(done); thread.join();
    }
    ~WorkerThread() { if (thread.joinable()) join(); }
};
std::atomic<unsigned> owners{0};
struct Owner { bool fail = false; };
bool release_owner(void* p) noexcept {
    auto* owner = static_cast<Owner*>(p);
    if (owner->fail) return false;
    delete owner; --owners; return true;
}
struct InstallationFixture {
    uint64_t id = 0, generation = 0, token = 0;
    Owner* owner = nullptr;
    explicit InstallationFixture(uint32_t slots = 1, bool sealed = true) {
        auto q = request(1); q.worker_capacity = slots; auto r = call(q); id = r.installation_id;
        assert(r.worker_capacity == slots && r.allocated_slots == slots && !r.worker_id && !r.code_generation);
        assert(mint_id(token).ok());
        owner = new Owner; ++owners;
        { Enrollment e(id); assert(e.outcome().ok());
          assert(e.commit(owner, release_owner, 1, &token, 1, generation).ok()); }
        if (sealed) seal();
    }
    mw_live_request_v1 operation(uint32_t op) const { auto q = request(op); q.installation_id = id; return q; }
    void seal() { auto q = operation(2); q.code_generation = generation; call(q); }
    mw_live_request_v1 registration(WorkerThread& w) {
        return w.run([&] {
            auto q = operation(3); q.code_generation = generation; q.tid = syscall(SYS_gettid);
            auto r = call(q); q.worker_id = r.worker_id; q.operation = 0;
            assert(r.allocated_slots && r.worker_capacity == r.allocated_slots && !r.task_id);
            return q;
        });
    }
    void retire() { call(operation(8)); owner = nullptr; }
};
void begin(WorkerThread& w, mw_live_request_v1& q) {
    q = w.run([&] { auto b = q; b.operation = 4; b.task_id = 0; b.invocation_id = 0;
        auto r = call(b); b.operation = 0; b.task_id = r.task_id;
        assert(!r.worker_capacity && !r.allocated_slots && !r.invocation_id); return b; });
}
void end(WorkerThread& w, mw_live_request_v1 q, uint32_t status = 0, uint32_t reason = 0) {
    w.run([=] { auto e = q; e.operation = 5; e.invocation_id = 0; call(e, status, reason); });
}
void publish_frame(WorkerThread& w, uint64_t token, uint32_t count = 1, Reason reason = Reason::None) {
    w.run([=] { enter(); mw_live_frame_v1 frames[129]{};
        for (auto& f : frames) f = {token, -1};
        publish(1, frames, count, reason); });
}
void retire(InstallationFixture& i, WorkerThread& w, const mw_live_request_v1& q) {
    w.join(); auto r = q; r.operation = 7; r.task_id = 0; r.invocation_id = 0;
    call(r); call(r); i.retire(); call(i.operation(8));
}
std::atomic<TestPoint> target{TestPoint::CopyDiscovered};
Rendezvous* rendezvous = nullptr;
void pause_hook(TestPoint point) { if (point == target.load()) rendezvous->pause(); }
void arm(TestPoint point, Rendezvous& barrier) {
    target.store(point); rendezvous = &barrier; test_hook(pause_hook);
}
void disarm(Rendezvous& barrier) { test_hook(nullptr); barrier.release(); }
bool cleanup(void*) noexcept { return true; }
void available_while_original_stopped() {
    // Removing publication or holding the gate over the original call must fail this test.
    (void)initialize({91, 37, static_cast<uint32_t>(getpid()), 3, true, true, true});
    auto q = request(1); q.worker_capacity = 1;
    auto reservation = call(q);
    uint64_t token = 0, generation = 0;
    assert(mint_id(token).ok());
    { Enrollment enrollment(reservation.installation_id);
      assert(enrollment.outcome().ok());
      assert(enrollment.commit(nullptr, cleanup, 1, &token, 1, generation).ok()); }
    q = request(2); q.installation_id = reservation.installation_id; q.code_generation = generation;
    call(q);
    Rendezvous original;
    mw_live_request_v1 read{};
    auto writer = std::async(std::launch::async, [&] {
        current_platform_thread();
        auto w = request(3); w.installation_id = q.installation_id; w.code_generation = generation;
        w.tid = syscall(SYS_gettid);
        auto registration = call(w);
        w.operation = 4; w.worker_id = registration.worker_id;
        auto task = call(w);
        w.operation = 0; w.task_id = task.task_id; read = w;
        enter(); mw_live_frame_v1 frame{token, -1}; publish(1, &frame, 1);
        original.pause(); // No gate may span this synthetic original-call interval.
        leave(); w.operation = 5; call(w); current_thread_exited();
    });
    original.wait();
    mw_live_snapshot_v1 snapshot{};
    assert(mazewall_live_copy_v1(&read, 88, &snapshot, 2152) == 0);
    assert(snapshot.status == 0 && snapshot.frame_count == 1 && snapshot.frames[0].method_token == token);
    auto cancellation = request(6); cancellation.installation_id = q.installation_id; call(cancellation);
    // This remains a synthetic interval, not actual syscall/proxy qualification.
    mw_live_snapshot_v1 revoked{};
    assert(mazewall_live_copy_v1(&read, 88, &revoked, 2152) == 4 && revoked.reason == 20);
    auto full = request(1); full.worker_capacity = 256; call(full, 7, 14);
    original.release();
    assert(writer.wait_for(10s) == std::future_status::ready); writer.get();
    auto retire = read; retire.operation = 7; retire.task_id = 0; call(retire);
    retire = request(8); retire.installation_id = q.installation_id; call(retire);
}

void lifecycle_and_capture() {
    InstallationFixture i;
    WorkerThread w;
    auto q = i.registration(w), no_task = q; no_task.task_id = 1;
    assert(w.run([] { return current_enrollment_owner(); }) == i.owner);
    assert(current_enrollment_owner() == nullptr);
    WorkerThread excess;
    excess.run([&] { auto r = i.operation(3); r.code_generation = i.generation; r.tid = syscall(SYS_gettid); call(r, 7, 14); });
    excess.join();
    read(no_task, 1, 1);
    auto reg = q; reg.operation = 3; reg.worker_id = 0;
    w.run([&] { call(reg, 4, 8); });
    begin(w, q); read(q, 1, 2);
    // Wrong actual thread cannot End or Begin the registered worker's task.
    auto bad = q; bad.operation = 5; call(bad, 4, 8);
    auto admission = q; admission.operation = 4; admission.task_id = 0;
    w.run([&] { call(admission, 4, 8); });
    publish_frame(w, i.token, 128); auto saved = read(q);
    assert(saved.frame_count == 128 && saved.parent_invocation_id == 0 && saved.binding == 1);
    auto exact = q; exact.invocation_id = saved.invocation_id; read(exact);
    ++exact.invocation_id; read(exact, 4, 8);
    for (auto fields : {0u, 1u, 2u, 3u, 4u, 5u}) {
        bad = q;
        switch (fields) {
            case 0: ++bad.process_cookie; break; case 1: ++bad.process_start_ticks; break;
            case 2: ++bad.tgid; break; case 3: ++bad.tid; break;
            case 4: ++bad.code_generation; break; case 5: ++bad.task_id; break;
        }
        read(bad, 4, 8);
    }
    test_process(getpid() + 1); read(q, 4, 8); test_process(getpid());
    for (auto failure : {Reason::CaptureFailed, Reason::Overflow, Reason::UnknownMethod}) {
        w.run([] { leave(); }); publish_frame(w, i.token, 1, failure);
        read(q, 2, static_cast<uint32_t>(failure));
    }
    w.run([] { leave(); }); publish_frame(w, i.token, 129); read(q, 2, 4);
    w.run([] { leave(); }); publish_frame(w, i.token + 9999); read(q, 2, 5);
    w.run([] { leave(); }); publish_frame(w, i.token);
    w.run([&] { enter(); mw_live_frame_v1 f{i.token, 0}; publish(1, &f, 1); }); read(q, 2, 6);
    w.run([&] { leave(); mw_live_frame_v1 f{i.token, 0}; publish(1, &f, 1); }); read(q, 2, 6);
    w.run([] { leave(); }); publish_frame(w, i.token);
    w.run([&] { poison(); mw_live_frame_v1 f{i.token, 0}; publish(1, &f, 1); }); read(q, 2, 6);
    w.run([] { leave(); }); read(q, 2, 6);
    publish_frame(w, i.token); read(q);
    end(w, q); read(q, 1, 1); end(w, q);
    w.run([] { leave(); });
    auto a = q; begin(w, q); publish_frame(w, i.token);
    auto b = read(q);
    // Identity errors on the actual worker must not mutate B, free or contended.
    for (unsigned field = 0; field < 6; ++field) {
        auto wrong = q;
        switch (field) {
            case 0: ++wrong.process_cookie; break; case 1: ++wrong.installation_id; break;
            case 2: ++wrong.worker_id; break; case 3: ++wrong.code_generation; break;
            case 4: ++wrong.tid; break; case 5: ++wrong.process_start_ticks; break;
        }
        end(w, wrong, 4, 8);
            assert(test_gate(q.worker_id, true)); end(w, wrong, 4, 8); test_gate(q.worker_id, false);
        assert(read(q).publication_id == b.publication_id);
    }
    end(w, a, 4, 8); assert(read(q).publication_id == b.publication_id);
    // A stale End must not even try the gate. A valid Busy End must mutate nothing.
    assert(test_gate(q.worker_id, true));
    end(w, a, 4, 8); end(w, q, 3, 7);
    w.run([&] { admission = q; admission.operation = 4; admission.task_id = 0; call(admission, 4, 8); });
    read(q, 3, 7); assert(test_gate(q.worker_id, false));
    w.run([&] { call(admission, 4, 8); });
    assert(read(q).publication_id == b.publication_id);
    end(w, q); end(w, q); read(q, 1, 1);
    w.run([] { leave(); });
    auto old = q; begin(w, q); end(w, old, 4, 8);
    assert(test_gate(q.worker_id, true)); end(w, old, 4, 8); test_gate(q.worker_id, false);
    // Caller-owned copies are independent of subsequent clear/mutation/reuse.
    assert(saved.frame_count == 128 && saved.frames[127].method_token == i.token);
    auto physical = q; physical.operation = 7; physical.task_id = 0; call(physical, 3, 21);
    call(i.operation(8), 3, 22); read(q, 4, 20);
    end(w, q); w.run([&] { admission = q; admission.operation = 4; admission.task_id = 0; call(admission, 4, 20); });
    i.owner->fail = true;
    w.join(); call(physical); call(i.operation(8), 3, 22);
    auto full = request(1); full.worker_capacity = 256; call(full, 7, 14);
    assert(owners.load() == 1); i.owner->fail = false; i.retire();
    assert(owners.load() == 0);
}

void probe_eligibility(const char* invalidation = nullptr) {
    assert(!probe_eligible());
    WorkerThread w;
    assert(!w.run([] { enter(); const bool result = probe_eligible(); leave(); return result; }));
    InstallationFixture i;
    auto q = i.registration(w);
    assert(!w.run([] { enter(); const bool result = probe_eligible(); leave(); return result; }));
    begin(w, q);
    w.run([] {
        assert(!probe_eligible());
        enter(); assert(probe_eligible());
        enter(); assert(!probe_eligible());
        leave(); assert(!probe_eligible()); // Never restore the parent's authority.
        leave(); assert(!probe_eligible());
        enter(); assert(probe_eligible());
        poison(); assert(!probe_eligible());
        leave();
    });
    // Eligibility is deliberately independent of payload-gate availability.
    assert(test_gate(q.worker_id, true));
    w.run([] { enter(); assert(probe_eligible()); leave(); });
    assert(test_gate(q.worker_id, false));
    end(w, q);
    w.run([] { enter(); assert(!probe_eligible()); leave(); });
    begin(w, q);
    w.run([] { enter(); assert(probe_eligible()); });
    if (invalidation) {
        if (std::strcmp(invalidation, "shutdown") == 0) shutdown();
        else if (std::strcmp(invalidation, "ids") == 0) {
            test_limits(MW_LIVE_MAX_ID, replacement_serial());
            uint64_t id = 0; assert(!mint_id(id).ok());
        } else if (std::strcmp(invalidation, "serial") == 0) {
            test_limits(0, MW_LIVE_MAX_ID); replacement();
        } else { assert(std::strcmp(invalidation, "replacement") == 0); replacement(); }
    } else call(i.operation(6));
    w.run([] { assert(!probe_eligible()); leave(); enter(); assert(!probe_eligible()); leave(); });
    end(w, q);
    w.run([] { current_thread_exited(); enter(); assert(!probe_eligible()); leave(); });
    retire(i, w, q);
}

void held_gates() {
    InstallationFixture i; WorkerThread w; auto q = i.registration(w); begin(w, q);
    publish_frame(w, i.token);
    Rendezvous writer;
    w.run([] { leave(); enter(); }); arm(TestPoint::PublishLocked, writer);
    auto publication = w.async([&] { mw_live_frame_v1 f{i.token, 0}; publish(1, &f, 1); });
    writer.wait(); read(q, 3, 7); disarm(writer); completed(publication); read(q);

    Rendezvous reader; arm(TestPoint::CopyLocked, reader);
    auto reading = std::async(std::launch::async, [&] { return read(q, 4, 20); });
    reader.wait();
    // Writer clears publication before failing its single gate attempt, without touching payload.
    w.run([] { leave(); }); publish_frame(w, i.token);
    w.join();
    auto retirement = q; retirement.operation = 7; retirement.task_id = 0;
    call(retirement, 3, 7); call(i.operation(8), 3, 22);
    auto full = request(1); full.worker_capacity = 256; call(full, 7, 14);
    disarm(reader); completed(reading);
    call(retirement); i.retire();
}

void reader_reuse(TestPoint point) {
    InstallationFixture old; WorkerThread a; auto q = old.registration(a); begin(a, q); publish_frame(a, old.token);
    Rendezvous barrier; arm(point, barrier);
    auto reading = std::async(std::launch::async, [&] {
        mw_live_snapshot_v1 s{}; auto result = mazewall_live_copy_v1(&q, 88, &s, 2152);
        assert(result == 4 && s.status == 4);
        const auto* p = reinterpret_cast<const unsigned char*>(&s);
        for (size_t n = 16; n < sizeof(s); ++n) assert(p[n] == 0);
    });
    barrier.wait(); retire(old, a, q); assert(owners.load() == 0);
    InstallationFixture fresh; WorkerThread b; auto next = fresh.registration(b); begin(b, next); publish_frame(b, fresh.token);
    assert(next.worker_id != q.worker_id && next.installation_id != q.installation_id && next.task_id != q.task_id);
    disarm(barrier); completed(reading); read(next);
    auto stale_retire = q; stale_retire.operation = 7; stale_retire.task_id = 0; call(stale_retire, 4, 8);
    call(old.operation(6), 4, 8); call(old.operation(8), 4, 8);
    retire(fresh, b, next);
}

void cancellation_and_commit_boundaries() {
    // Revocation does not require the worker's gate or cooperation; every task is revoked.
    InstallationFixture i(2); WorkerThread a, b;
    auto qa = i.registration(a), qb = i.registration(b); begin(a, qa); begin(b, qb);
    publish_frame(a, i.token); publish_frame(b, i.token);
    assert(test_gate(qa.worker_id, true)); call(i.operation(6)); call(i.operation(6));
    test_gate(qa.worker_id, false); read(qa, 4, 20); read(qb, 4, 20);
    end(a, qa); end(b, qb);
    auto new_task = qb; new_task.operation = 4; new_task.task_id = 0;
    b.run([&] { call(new_task, 4, 20); });
    a.join(); b.join();
    for (auto q : {qa, qb}) { q.operation = 7; q.task_id = 0; call(q); }
    i.retire(); call(i.operation(6));

    // Paused old cancellation must not revoke a new owner after physical-cell reuse.
    InstallationFixture old;
    Rendezvous cancel; arm(TestPoint::InvalidateCAS, cancel);
    auto invalidation = std::async(std::launch::async, [&] { call(old.operation(6), 4, 8); });
    cancel.wait(); test_hook(nullptr); old.retire();
    InstallationFixture fresh; WorkerThread w; auto q = fresh.registration(w); begin(w, q); publish_frame(w, fresh.token);
    cancel.release(); completed(invalidation); read(q); retire(fresh, w, q);

    // Staging -> Sealed is the one phase change allowed between op6's two strong CAS attempts.
    InstallationFixture staging(1, false);
    Rendezvous sealing_race; arm(TestPoint::InvalidateCAS, sealing_race);
    auto cancel_staging = std::async(std::launch::async, [&] { call(staging.operation(6)); });
    sealing_race.wait(); staging.seal(); disarm(sealing_race); completed(cancel_staging);
    auto stale_seal = staging.operation(2); stale_seal.code_generation = staging.generation;
    call(stale_seal, 4, 20); staging.retire();

    // Seal's exact CAS may not overwrite cancellation, even with the admin gate held.
    InstallationFixture staged(1, false);
    Rendezvous sealing; arm(TestPoint::SealCAS, sealing);
    auto sealed = std::async(std::launch::async, [&] {
        auto q = staged.operation(2); q.code_generation = staged.generation; call(q, 4, 20);
    });
    sealing.wait(); call(staged.operation(6)); disarm(sealing); completed(sealed); staged.retire();
    InstallationFixture seal_wins; call(seal_wins.operation(6));
    auto seal = seal_wins.operation(2); seal.code_generation = seal_wins.generation; call(seal, 4, 20); seal_wins.retire();

    InstallationFixture registering; WorkerThread registering_worker;
    Rendezvous owned; arm(TestPoint::RegisterOwned, owned);
    auto registering_future = registering_worker.async([&] {
        auto q = registering.operation(3); q.code_generation = registering.generation; q.tid = syscall(SYS_gettid); call(q, 4, 20);
    });
    owned.wait(); call(registering.operation(6)); call(registering.operation(8), 3, 7);
    assert(owners.load() == 1); disarm(owned); completed(registering_future);
    registering_worker.join(); registering.retire();

    // Failed registration must not overwrite the still-current retired-worker tombstone.
    InstallationFixture retained; WorkerThread first;
    auto retired_identity = retained.registration(first); first.join();
    auto old_worker = retired_identity; old_worker.operation = 7; call(old_worker);
    WorkerThread aborted;
    Rendezvous rollback; arm(TestPoint::RegisterOwned, rollback);
    auto aborting = aborted.async([&] {
        auto q = retained.operation(3); q.code_generation = retained.generation;
        q.tid = syscall(SYS_gettid); call(q, 4, 20);
    });
    rollback.wait(); call(retained.operation(6)); disarm(rollback); completed(aborting);
    call(old_worker); aborted.join(); retained.retire();

    // If revocation wins after Register's last admission check, its owner remains counted.
    InstallationFixture committed; WorkerThread accountable;
    Rendezvous accounting; arm(TestPoint::RegisterCommit, accounting);
    auto committing = accountable.async([&] {
        auto q = committed.operation(3); q.code_generation = committed.generation; q.tid = syscall(SYS_gettid);
        auto r = call(q); q.operation = 0; q.worker_id = r.worker_id; return q;
    });
    accounting.wait(); call(committed.operation(6)); call(committed.operation(8), 3, 7);
    disarm(accounting); auto accounted = completed(committing); accounted.task_id = 1;
    read(accounted, 4, 20); retire(committed, accountable, accounted);

    InstallationFixture binding; WorkerThread binding_worker; auto unbound = binding.registration(binding_worker);
    Rendezvous task_binding; arm(TestPoint::BeginBound, task_binding);
    auto beginning = binding_worker.async([&] { auto q = unbound; q.operation = 4; call(q, 4, 20); });
    task_binding.wait(); call(binding.operation(6)); disarm(task_binding); completed(beginning);
    unbound.task_id = 1; read(unbound, 4, 20); retire(binding, binding_worker, unbound);

    InstallationFixture pre; WorkerThread delayed;
    Rendezvous before; arm(TestPoint::RegisterDiscovered, before);
    auto registration = delayed.async([&] {
        auto q = pre.operation(3); q.code_generation = pre.generation; q.tid = syscall(SYS_gettid); call(q, 4, 8);
    });
    before.wait(); pre.retire(); InstallationFixture replacement;
    disarm(before); completed(registration); delayed.join(); replacement.retire();
}

void generation_and_cleanup(bool stop) {
    InstallationFixture i; WorkerThread w; auto q = i.registration(w); begin(w, q); publish_frame(w, i.token);
    if (stop) shutdown(); else replacement();
    read(q, 4, stop ? 10 : 9);
    end(w, q); end(w, q); // Cleanup uses original registration G, even after invalidation.
    auto b = q; b.operation = 4; b.task_id = 0;
    w.run([&] { call(b, 4, stop ? 10 : 9); });
    call(i.operation(6)); retire(i, w, q);
}

void invalidation_interleavings(bool stop) {
    const auto reason = stop ? 10u : 9u;
    // Publication's commit recheck catches invalidation while capture was outside the gate.
    InstallationFixture i; WorkerThread w; auto q = i.registration(w); begin(w, q);
    w.run([] { enter(); });
    Rendezvous publication; arm(TestPoint::PublishLocked, publication);
    auto publishing = w.async([&] { mw_live_frame_v1 frame{i.token, 0}; publish(1, &frame, 1); });
    publication.wait();
    if (stop) shutdown(); else replacement();
    disarm(publication); completed(publishing); read(q, 4, reason);
    end(w, q); retire(i, w, q);
}

void read_invalidation(bool stop) {
    InstallationFixture i; WorkerThread w; auto q = i.registration(w); begin(w, q); publish_frame(w, i.token);
    Rendezvous reader; arm(TestPoint::CopyReleased, reader);
    auto reading = std::async(std::launch::async, [&] { read(q, 4, stop ? 10 : 9); });
    reader.wait(); if (stop) shutdown(); else replacement();
    disarm(reader); completed(reading); end(w, q); retire(i, w, q);
}

void readiness_failure(const char* mode) {
    Startup startup{91, 37, static_cast<uint32_t>(getpid()), 3, true, true, true};
    uint32_t reason = 19;
    if (std::strcmp(mode, "runtime") == 0) { startup.runtime_verified = false; reason = 12; }
    else if (std::strcmp(mode, "binding") == 0) { startup.binding_mask = 0; reason = 11; }
    else if (std::strcmp(mode, "helper") == 0) startup.helper_registered = false;
    else startup.hooks_ready = false;
    assert(!initialize(startup));
    assert(!initialize({91, 37, static_cast<uint32_t>(getpid()), 3, true, true, true}));
    mw_live_info_v1 info; std::memset(&info, 0xAB, sizeof(info));
    assert(mazewall_live_info_v1(&info, 64) == 5 && info.status == 5 && info.reason == reason);
    const auto* p = reinterpret_cast<const unsigned char*>(&info);
    for (size_t n = 16; n < sizeof(info); ++n) assert(p[n] == 0);
    auto q = request(1); q.worker_capacity = 1; call(q, 5, reason);
    q = request(0); q.installation_id = 1; q.worker_id = 2; q.task_id = 3; q.code_generation = 4; q.tid = 5;
    read(q, 5, reason);
    q.invocation_id = 6;
    assert(match_arguments(q, 7, Arguments{ArgumentKind::Open0, 0, 0, 0, 0}) == MatchResult::Unavailable);
}

void malformed_buffers() {
    // Every unused/required field for every operation, not just opcode validation.
    const uint32_t masks[] = {47, 64, 9, 41, 43, 47, 1, 43, 1};
    for (uint32_t op = 0; op <= 8; ++op) {
        auto q = request(op); q.installation_id = 1; q.worker_id = 2; q.task_id = 3;
        q.code_generation = 4; q.tid = 5; q.worker_capacity = 1;
        uint64_t* ids[] = {&q.installation_id, &q.worker_id, &q.task_id, &q.code_generation, &q.invocation_id};
        for (unsigned n = 0; n < 5; ++n) if (!(masks[op] & (1u << n))) *ids[n] = 0;
        if (!(masks[op] & 32)) q.tid = 0;
        if (!(masks[op] & 64)) q.worker_capacity = 0;
        for (unsigned n = 0; n < 7; ++n) {
            if (op == 0 && n == 4) continue; // Optional expected invocation.
            auto bad = q;
            uint64_t* fields[] = {&bad.installation_id, &bad.worker_id, &bad.task_id, &bad.code_generation, &bad.invocation_id};
            auto value = (masks[op] & (1u << n)) ? 0u : 1u;
            if (n < 5) *fields[n] = value;
            else if (n == 5) bad.tid = value; else bad.worker_capacity = value;
            if (op) call(bad, 6, 16); else read(bad, 6, 16);
        }
    }
    auto q = request(1); q.worker_capacity = 1;
    for (auto value : {1u, UINT32_MAX}) {
        auto bad = q; bad.reserved0 = value; call(bad, 6, 16);
        bad = q; bad.reserved1 = value; call(bad, 6, 16);
    }
    auto bad = q; bad.worker_capacity = 257; call(bad, 6, 16);
    bad = q; bad.abi_version = 2; call(bad, 5, 13);
    bad = q; bad.struct_bytes = 87; call(bad, 5, 13);
    bad = q; bad.operation = 9; call(bad, 6, 16);
    bad = q; bad.operation = 0; call(bad, 6, 16);
    struct alignas(8) Guard { uint64_t before; mw_live_snapshot_v1 s; uint64_t after; } g{};
    g.before = g.after = UINT64_C(0x1234567812345678);
    auto copy = request(0); copy.installation_id = 1; copy.worker_id = 2; copy.task_id = 3; copy.code_generation = 4; copy.tid = 5;
    for (auto bytes : {0u, 87u, 89u, UINT32_MAX}) {
        assert(mazewall_live_copy_v1(&copy, bytes, &g.s, 2152) == 6);
        assert(g.s.status == 6 && g.s.reason == 16);
    }
    alignas(8) unsigned char storage[2168]; std::memset(storage, 0xA5, sizeof(storage));
    for (auto bytes : {0u, 2151u, 2153u, UINT32_MAX}) assert(mazewall_live_copy_v1(&copy, 88, storage, bytes) == 6);
    assert(mazewall_live_copy_v1(&copy, 88, storage + 1, 2152) == 6);
    assert(mazewall_live_copy_v1(&copy, 88, nullptr, 2152) == 6);
    for (auto c : storage) assert(c == 0xA5);
    // Exact valid output extents: failure headers/payload cannot overwrite either guard.
    for (unsigned kind = 0; kind < 3; ++kind) {
        std::memset(storage, 0xA5, sizeof(storage));
        const unsigned size = kind == 0 ? 88 : kind == 1 ? 2152 : 64;
        if (kind == 0) assert(mazewall_live_lifecycle_v1(nullptr, 88, storage + 8, size) == 6);
        else if (kind == 1) assert(mazewall_live_copy_v1(nullptr, 88, storage + 8, size) == 6);
        else assert(mazewall_live_info_v1(storage + 8, size) == 0);
        for (size_t n = 0; n < 8; ++n) assert(storage[n] == 0xA5);
        for (size_t n = 8 + size; n < sizeof(storage); ++n) assert(storage[n] == 0xA5);
        if (kind < 2) for (size_t n = 24; n < 8 + size; ++n) assert(storage[n] == 0);
    }
    std::memset(storage, 0xA5, sizeof(storage));
    assert(mazewall_live_copy_v1(nullptr, 88, &g.s, 2152) == 6);
    assert(mazewall_live_copy_v1(storage + 1, 88, &g.s, 2152) == 6);
    assert(g.before == g.after && g.after == UINT64_C(0x1234567812345678));
    mw_live_receipt_v1 r{};
    for (auto bytes : {0u, 87u, 89u, UINT32_MAX}) {
        assert(mazewall_live_lifecycle_v1(&q, bytes, &r, 88) == 6);
        assert(mazewall_live_lifecycle_v1(&q, 88, storage, bytes) == 6);
    }
    assert(mazewall_live_lifecycle_v1(nullptr, 88, &r, 88) == 6);
    assert(mazewall_live_lifecycle_v1(storage + 1, 88, &r, 88) == 6);
    assert(mazewall_live_lifecycle_v1(&q, 88, nullptr, 88) == 6);
    assert(mazewall_live_lifecycle_v1(&q, 88, storage + 1, 88) == 6);
    mw_live_info_v1 info{};
    assert(mazewall_live_info_v1(&info, 64) == 0);
    assert(info.arch == 1 && info.max_workers == 256 && info.max_classes == 512 && info.max_methods == 4096 && info.max_frames == 128);
    assert(info.binding_mask == 3 && info.reserved == 0 && info.process_cookie == 91 && info.process_start_ticks == 37);
    for (auto bytes : {0u, 63u, 65u, UINT32_MAX}) assert(mazewall_live_info_v1(storage, bytes) == 6);
    assert(mazewall_live_info_v1(nullptr, 64) == 6 && mazewall_live_info_v1(storage + 1, 64) == 6);
    for (auto c : storage) assert(c == 0xA5);
    for (auto id : {MW_LIVE_MAX_ID + 1, UINT64_C(9223372036854775807)}) {
        auto b = copy; b.worker_id = id; read(b, 6, 16);
        b = request(6); b.installation_id = id; call(b, 6, 16);
    }
}

void bounds_and_enrollment() {
    auto reserve = request(1); reserve.worker_capacity = 256; auto all = call(reserve);
    auto extra = request(1); extra.worker_capacity = 1; call(extra, 7, 14);
    auto seal = request(2); seal.installation_id = all.installation_id; seal.code_generation = 1; call(seal, 4, 8);
    auto retire = request(8); retire.installation_id = all.installation_id; call(retire);
    std::vector<uint64_t> installations;
    for (unsigned n = 0; n < 256; ++n) installations.push_back(call(extra).installation_id);
    call(extra, 7, 14);
    for (auto id : installations) { retire.installation_id = id; call(retire); }
    auto staged = call(extra).installation_id;
    uint64_t tokens[4096]{}; for (auto& token : tokens) assert(mint_id(token).ok());
    uint64_t generation = 0;
    { Enrollment e(staged); assert(e.outcome().ok());
      assert(e.commit(nullptr, cleanup, 513, tokens, 1, generation).status == Status::InvalidArgument);
      assert(e.commit(nullptr, cleanup, 1, tokens, 4097, generation).status == Status::InvalidArgument);
      assert(e.commit(nullptr, cleanup, 0, tokens, 1, generation).status == Status::InvalidArgument);
      assert(e.commit(nullptr, cleanup, 1, nullptr, 1, generation).status == Status::InvalidArgument);
      assert(e.commit(nullptr, cleanup, 1, tokens, 0, generation).status == Status::InvalidArgument);
      auto saved = tokens[1]; tokens[1] = tokens[0];
      assert(e.commit(nullptr, cleanup, 1, tokens, 2, generation).status == Status::InvalidArgument); tokens[1] = saved;
      auto token = MW_LIVE_MAX_ID + 1;
      assert(e.commit(nullptr, cleanup, 1, &token, 1, generation).status == Status::InvalidArgument);
      assert(e.commit(nullptr, cleanup, 512, tokens, 4096, generation).ok());
      assert(e.commit(nullptr, cleanup, 512, tokens, 4096, generation).status == Status::Stale); }
    seal.installation_id = staged;
    // The failed duplicate enrollment cleared the output only, not the committed association.
    uint64_t expected_generation = tokens[4095] + 1; seal.code_generation = expected_generation;
    call(seal); call(seal);
    retire.installation_id = staged; call(retire);
}

void isolated_exhaustion(bool serial_overflow) {
    InstallationFixture i; WorkerThread w; auto q = i.registration(w); begin(w, q); publish_frame(w, i.token);
    if (serial_overflow) {
        test_limits(10000, MW_LIVE_MAX_ID); replacement(); replacement();
        assert(replacement_serial() == MW_LIVE_MAX_ID);
    }
    else {
        test_limits(MW_LIVE_MAX_ID - 1, replacement_serial());
        uint64_t id = 0; assert(mint_id(id).ok() && id == MW_LIVE_MAX_ID);
        assert(mint_id(id).status == Status::Exhausted && id == 0);
        assert(mint_id(id).status == Status::Exhausted && id == 0);
    }
    read(q, 7, 15); end(w, q); call(i.operation(6)); retire(i, w, q);
    auto reserve = request(1); reserve.worker_capacity = 1; call(reserve, 7, 15);
}

void argument_provenance() {
    InstallationFixture i; WorkerThread w; auto q = i.registration(w); begin(w, q);
    const Arguments args{ArgumentKind::Open0, 0xfedcba9876543210ULL, 0x80000000U, 0xffffffffU, 0};
    w.run([&] { enter(); mw_live_frame_v1 frame{i.token, -1}; publish(1, &frame, 1, Reason::None, args); });
    const auto snapshot = read(q); q.invocation_id = snapshot.invocation_id;
    assert(match_arguments(q, snapshot.publication_id, args) == MatchResult::Match);
    auto changed = args; changed.path_bits ^= 1;
    assert(match_arguments(q, snapshot.publication_id, changed) == MatchResult::Unavailable);
    changed = args; changed.flags_bits ^= 1;
    assert(match_arguments(q, snapshot.publication_id, changed) == MatchResult::Unavailable);
    changed = args; changed.mode_bits ^= 1;
    assert(match_arguments(q, snapshot.publication_id, changed) == MatchResult::Unavailable);
    changed = args; changed.dirfd_bits = 1;
    assert(match_arguments(q, snapshot.publication_id, changed) == MatchResult::Unavailable);
    assert(match_arguments(q, 0, args) == MatchResult::Unavailable);
    assert(match_arguments(q, snapshot.publication_id + 1, args) == MatchResult::Unavailable);
    auto invalid = q; invalid.invocation_id = 0;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.invocation_id = MW_LIVE_MAX_ID + 1;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.abi_version = 2;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.struct_bytes--;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.operation = 1;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.reserved0 = 1;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.reserved1 = 1;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.worker_capacity = 1;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.task_id = 0;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.worker_id = MW_LIVE_MAX_ID + 1;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.installation_id++;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.code_generation++;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.tid++;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.process_cookie++;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    changed = args; changed.kind = ArgumentKind::None;
    assert(match_arguments(q, snapshot.publication_id, changed) == MatchResult::Unavailable);
    changed.kind = static_cast<ArgumentKind>(99);
    assert(match_arguments(q, snapshot.publication_id, changed) == MatchResult::Unavailable);
    changed.kind = ArgumentKind::Openat0;
    assert(match_arguments(q, snapshot.publication_id, changed) == MatchResult::Unavailable);
    for (auto member : {&mw_live_request_v1::installation_id, &mw_live_request_v1::worker_id,
                        &mw_live_request_v1::task_id, &mw_live_request_v1::code_generation,
                        &mw_live_request_v1::invocation_id}) {
        invalid = q; invalid.*member = 0;
        assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
        invalid.*member = MW_LIVE_MAX_ID + 1;
        assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    }
    invalid = q; invalid.tid = 0;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.tgid = 0;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    invalid = q; invalid.process_start_ticks = 0;
    assert(match_arguments(invalid, snapshot.publication_id, args) == MatchResult::Unavailable);
    assert(match_arguments(q, MW_LIVE_MAX_ID + 1, args) == MatchResult::Unavailable);
    assert(match_arguments(q, snapshot.publication_id, args) == MatchResult::Match);
    assert(test_gate(q.worker_id, true));
    assert(match_arguments(q, snapshot.publication_id, args) == MatchResult::Unavailable);
    assert(test_gate(q.worker_id, false));
    w.run([] { leave(); });
    assert(match_arguments(q, snapshot.publication_id, args) == MatchResult::Unavailable);
    w.run([&] { enter(); mw_live_frame_v1 frames[128]{};
        for (auto& frame : frames) frame = {i.token, 0};
        publish(1, frames, 128, Reason::None, args); });
    q.invocation_id = 0; const auto full = read(q); q.invocation_id = full.invocation_id;
    assert(full.frame_count == 128 && match_arguments(q, full.publication_id, args) == MatchResult::Match);
    assert(match_arguments(q, snapshot.publication_id, args) == MatchResult::Unavailable);
    w.run([] { poison(); });
    assert(match_arguments(q, full.publication_id, args) == MatchResult::Unavailable);
    w.run([] { leave(); });
    w.run([&] { enter(); mw_live_frame_v1 frames[129]{};
        for (auto& frame : frames) frame = {i.token, 0};
        publish(1, frames, 129, Reason::None, args); });
    assert(match_arguments(q, full.publication_id, args) == MatchResult::Unavailable);
    w.run([] { leave(); });
    q.invocation_id = 0; end(w, q); retire(i, w, q);
}

void argument_match_revocation(TestPoint point) {
    InstallationFixture i; WorkerThread w; auto q = i.registration(w); begin(w, q);
    const Arguments args{ArgumentKind::Open0, 7, 8, 9, 0};
    w.run([&] { enter(); mw_live_frame_v1 frame{i.token, 0}; publish(1, &frame, 1, Reason::None, args); });
    auto snapshot = read(q); q.invocation_id = snapshot.invocation_id;
    Rendezvous barrier; arm(point, barrier);
    auto match = std::async(std::launch::async, [&] { return match_arguments(q, snapshot.publication_id, args); });
    barrier.wait(); w.run([] { leave(); }); disarm(barrier);
    assert(completed(match) == MatchResult::Unavailable);
    q.invocation_id = 0; end(w, q); retire(i, w, q);
}

void argument_lifecycle_revocation(int mode, TestPoint point) {
    InstallationFixture i; WorkerThread w; auto q = i.registration(w); begin(w, q);
    const Arguments args{ArgumentKind::Open0, 42, 0, 0, 0};
    w.run([&] { enter(); mw_live_frame_v1 frame{i.token, 0}; publish(1, &frame, 1, Reason::None, args); });
    const auto snapshot = read(q); q.invocation_id = snapshot.invocation_id;
    Rendezvous barrier; arm(point, barrier);
    auto match = std::async(std::launch::async, [&] { return match_arguments(q, snapshot.publication_id, args); });
    barrier.wait();
    if (mode == 0) { auto e = q; e.invocation_id = 0; end(w, e); }
    else if (mode == 1) call(i.operation(6));
    else if (mode == 2) replacement();
    else if (mode == 3) shutdown();
    else if (mode == 4) w.run([] { enter(); });
    else if (mode == 5) w.run([] { poison(); });
    else if (mode == 6) w.join();
    else if (mode == 7) { test_limits(MW_LIVE_MAX_ID, replacement_serial()); uint64_t id = 0; assert(!mint_id(id).ok()); }
    else w.run([&] { publish(1, nullptr, 0, Reason::CaptureFailed, args); });
    disarm(barrier); assert(completed(match) == MatchResult::Unavailable);
    assert(match_arguments(q, snapshot.publication_id, args) == MatchResult::Unavailable);
}
void isolated_argument_lifecycle() {
    for (int mode = 0; mode < 9; ++mode) {
        for (auto point : {TestPoint::MatchReleased, TestPoint::MatchLive, TestPoint::MatchControl,
                           TestPoint::MatchTask, TestPoint::MatchPublication}) {
            const auto pid = fork(); assert(pid >= 0);
            if (pid == 0) { test_process(static_cast<uint32_t>(getpid())); argument_lifecycle_revocation(mode, point); _exit(0); }
            int status = 0; assert(waitpid(pid, &status, 0) == pid);
            assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
        }
    }
}

void argument_retirement_reuse() {
    const Arguments args{ArgumentKind::Openat0, 0x1234, 0xffffffffU, 0x80000000U, 0xffffff9cU};
    InstallationFixture first; WorkerThread old_worker; auto old = first.registration(old_worker); begin(old_worker, old);
    old_worker.run([&] { enter(); mw_live_frame_v1 frame{first.token, 0}; publish(2, &frame, 1, Reason::None, args); });
    auto prior = read(old); old.invocation_id = prior.invocation_id;
    assert(match_arguments(old, prior.publication_id, args) == MatchResult::Match);
    old_worker.run([] { leave(); }); auto old_end = old; old_end.invocation_id = 0; end(old_worker, old_end);
    retire(first, old_worker, old_end);
    assert(match_arguments(old, prior.publication_id, args) == MatchResult::Unavailable);
    InstallationFixture second; WorkerThread new_worker; auto fresh = second.registration(new_worker); begin(new_worker, fresh);
    new_worker.run([&] { enter(); mw_live_frame_v1 frame{second.token, 0}; publish(2, &frame, 1, Reason::None, args); });
    auto current = read(fresh); fresh.invocation_id = current.invocation_id;
    assert(match_arguments(old, prior.publication_id, args) == MatchResult::Unavailable);
    assert(match_arguments(fresh, current.publication_id, args) == MatchResult::Match);
    assert(match_arguments(fresh, prior.publication_id, args) == MatchResult::Unavailable);
    new_worker.run([] { leave(); }); fresh.invocation_id = 0; end(new_worker, fresh); retire(second, new_worker, fresh);
}

void invalid_published_arguments_preserve_snapshot() {
    InstallationFixture i; WorkerThread w; auto q = i.registration(w); begin(w, q);
    const Arguments good{ArgumentKind::Open0, 42, 7, 8, 0};
    const Arguments invalid[] = {
        {}, {static_cast<ArgumentKind>(99), 42, 7, 8, 0},
        {ArgumentKind::Openat0, 42, 7, 8, 0}, {ArgumentKind::Open0, 42, 7, 8, 1}
    };
    for (const auto& published : invalid) {
        w.run([&] { enter(); mw_live_frame_v1 frame{i.token, -1};
            publish(1, &frame, 1, Reason::None, published); });
        q.invocation_id = 0; const auto snapshot = read(q);
        assert(snapshot.binding == 1 && snapshot.frame_count == 1 && snapshot.frames[0].method_token == i.token &&
               snapshot.frames[0].location == -1);
        q.invocation_id = snapshot.invocation_id;
        for (const auto& candidate : invalid)
            assert(match_arguments(q, snapshot.publication_id, candidate) == MatchResult::Unavailable);
        assert(match_arguments(q, snapshot.publication_id, good) == MatchResult::Unavailable);
        w.run([] { leave(); });
    }
    w.run([&] { enter(); mw_live_frame_v1 frame{i.token, -1}; publish(1, &frame, 1, Reason::None, good); });
    q.invocation_id = 0; const auto snapshot = read(q); q.invocation_id = snapshot.invocation_id;
    assert(match_arguments(q, snapshot.publication_id, good) == MatchResult::Match);
    w.run([] { leave(); }); q.invocation_id = 0; end(w, q); retire(i, w, q);
}

static_assert(sizeof(mw_live_match_request_v1) == 128 && alignof(mw_live_match_request_v1) == 8);
static_assert(offsetof(mw_live_match_request_v1, request) == 0);
static_assert(offsetof(mw_live_match_request_v1, expected_publication) == 88);
static_assert(offsetof(mw_live_match_request_v1, kind) == 96);
static_assert(offsetof(mw_live_match_request_v1, flags_bits) == 100);
static_assert(offsetof(mw_live_match_request_v1, mode_bits) == 104);
static_assert(offsetof(mw_live_match_request_v1, dirfd_bits) == 108);
static_assert(offsetof(mw_live_match_request_v1, path_bits) == 112);
static_assert(offsetof(mw_live_match_request_v1, reserved) == 120);
static_assert(sizeof(mw_live_match_result_v1) == 16 && alignof(mw_live_match_result_v1) == 8);
static_assert(offsetof(mw_live_match_result_v1, abi_version) == 0);
static_assert(offsetof(mw_live_match_result_v1, struct_bytes) == 4);
static_assert(offsetof(mw_live_match_result_v1, status) == 8);
static_assert(offsetof(mw_live_match_result_v1, reserved) == 12);
void native_match_wrapper() {
    InstallationFixture i; WorkerThread w; auto q = i.registration(w); begin(w, q);
    const Arguments args{ArgumentKind::Open0, 42, 7, 8, 0};
    w.run([&] { enter(); mw_live_frame_v1 frame{i.token, -1}; publish(1, &frame, 1, Reason::None, args); });
    const auto snapshot = read(q); q.invocation_id = snapshot.invocation_id;
    mw_live_match_request_v1 input{}; input.request = q; input.expected_publication = snapshot.publication_id;
    input.kind = 1; input.path_bits = 42; input.flags_bits = 7; input.mode_bits = 8;
    struct Output { uint64_t before; mw_live_match_result_v1 result; uint64_t after; } output{0x1234, {}, 0x5678};
    const auto unchanged = input;
    assert(mazewall_live_match_arguments_v1(&input, sizeof(input), &output.result, sizeof(output.result)) == MW_LIVE_MATCH_MATCH);
    assert(output.result.abi_version == 1 && output.result.struct_bytes == 16 && output.result.status == MW_LIVE_MATCH_MATCH && !output.result.reserved);
    assert(output.before == 0x1234 && output.after == 0x5678 && std::memcmp(&input, &unchanged, sizeof(input)) == 0);
    input.path_bits ^= 1; const auto altered = input;
    assert(mazewall_live_match_arguments_v1(&input, sizeof(input), &output.result, sizeof(output.result)) == MW_LIVE_MATCH_UNAVAILABLE);
    assert(output.result.status == MW_LIVE_MATCH_UNAVAILABLE && output.before == 0x1234 && output.after == 0x5678);
    assert(std::memcmp(&input, &altered, sizeof(input)) == 0);
    auto check = [&](const mw_live_match_request_v1& in, uint32_t status) {
        output.result = {}; const auto saved = in;
        assert(mazewall_live_match_arguments_v1(&in, sizeof(in), &output.result, sizeof(output.result)) == status);
        assert(output.result.abi_version == 1 && output.result.struct_bytes == 16 && output.result.status == status && !output.result.reserved);
        assert(output.before == 0x1234 && output.after == 0x5678 && std::memcmp(&in, &saved, sizeof(in)) == 0);
    };
    input = unchanged;
    for (auto member : {&mw_live_request_v1::installation_id, &mw_live_request_v1::worker_id,
                        &mw_live_request_v1::task_id, &mw_live_request_v1::code_generation, &mw_live_request_v1::invocation_id}) {
        auto bad = input; bad.request.*member = 0; check(bad, MW_LIVE_MATCH_MALFORMED);
        bad.request.*member = MW_LIVE_MAX_ID + 1; check(bad, MW_LIVE_MATCH_MALFORMED);
    }
    auto bad = input; bad.request.abi_version = 2; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.request.struct_bytes--; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.request.operation = 1; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.request.reserved0 = 1; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.request.reserved1 = 1; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.request.worker_capacity = 1; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.request.process_cookie = 0; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.request.process_start_ticks = 0; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.request.tgid = 0; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.request.tgid = uint32_t(INT32_MAX) + 1; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.request.tid = 0; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.request.tid = uint32_t(INT32_MAX) + 1; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.request.process_cookie = UINT64_C(1) << 63; check(bad, MW_LIVE_MATCH_UNAVAILABLE);
    bad = input; bad.request.process_start_ticks = UINT64_C(1) << 63; check(bad, MW_LIVE_MATCH_UNAVAILABLE);
    bad = input; ++bad.request.tid; check(bad, MW_LIVE_MATCH_UNAVAILABLE);
    bad = input; bad.expected_publication = 0; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad.expected_publication = MW_LIVE_MAX_ID + 1; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.reserved = 1; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.kind = 0; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad.kind = 3; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.dirfd_bits = 1; check(bad, MW_LIVE_MATCH_MALFORMED);
    bad = input; bad.kind = 2; check(bad, MW_LIVE_MATCH_UNAVAILABLE);
    bad = input; bad.flags_bits ^= 1; check(bad, MW_LIVE_MATCH_UNAVAILABLE);
    bad = input; bad.mode_bits ^= 1; check(bad, MW_LIVE_MATCH_UNAVAILABLE);
    bad = input; ++bad.request.tgid; check(bad, MW_LIVE_MATCH_UNAVAILABLE);
    for (auto member : {&mw_live_request_v1::installation_id, &mw_live_request_v1::worker_id,
                        &mw_live_request_v1::task_id, &mw_live_request_v1::code_generation}) {
        bad = input; ++(bad.request.*member); check(bad, MW_LIVE_MATCH_UNAVAILABLE);
    }
    bad = input; ++bad.request.process_cookie; check(bad, MW_LIVE_MATCH_UNAVAILABLE);
    assert(test_gate(q.worker_id, true)); check(input, MW_LIVE_MATCH_UNAVAILABLE); assert(test_gate(q.worker_id, false));
    alignas(8) unsigned char buffers[192]; std::memset(buffers, 0xA5, sizeof(buffers));
    auto unchanged_buffer = [&] { for (auto byte : buffers) assert(byte == 0xA5); };
    assert(mazewall_live_match_arguments_v1(&input, sizeof(input), nullptr, 16) == MW_LIVE_MATCH_MALFORMED);
    assert(mazewall_live_match_arguments_v1(&input, sizeof(input), buffers + 1, 16) == MW_LIVE_MATCH_MALFORMED); unchanged_buffer();
    assert(mazewall_live_match_arguments_v1(&input, sizeof(input), buffers, 15) == MW_LIVE_MATCH_MALFORMED); unchanged_buffer();
    for (auto pair : {std::pair<size_t, size_t>{0, 0}, {0, 8}, {8, 0}}) {
        assert(mazewall_live_match_arguments_v1(buffers + pair.first, 128, buffers + pair.second, 16) == MW_LIVE_MATCH_MALFORMED);
        unchanged_buffer();
    }
    assert(mazewall_live_match_arguments_v1(buffers, 8, buffers, 16) == MW_LIVE_MATCH_MALFORMED); unchanged_buffer();
    assert(mazewall_live_match_arguments_v1(buffers + 1, 8, buffers, 16) == MW_LIVE_MATCH_MALFORMED); unchanged_buffer();
    assert(mazewall_live_match_arguments_v1(buffers, 128, buffers + 128, 16) == MW_LIVE_MATCH_MALFORMED);
    auto* adjacent = reinterpret_cast<mw_live_match_result_v1*>(buffers + 128);
    assert(adjacent->abi_version == 1 && adjacent->struct_bytes == 16 && adjacent->status == MW_LIVE_MATCH_MALFORMED && !adjacent->reserved);
    for (size_t n = 0; n < 128; ++n) assert(buffers[n] == 0xA5);
    std::memset(buffers, 0xA5, sizeof(buffers));
    const auto* overflow = reinterpret_cast<const void*>(UINTPTR_MAX - 7);
    assert(mazewall_live_match_arguments_v1(overflow, 128, buffers, 16) == MW_LIVE_MATCH_MALFORMED); unchanged_buffer();
    auto* overflow_out = reinterpret_cast<void*>(UINTPTR_MAX - 7);
    assert(mazewall_live_match_arguments_v1(&input, sizeof(input), overflow_out, 16) == MW_LIVE_MATCH_MALFORMED);
    assert(mazewall_live_match_arguments_v1(nullptr, 0, &output.result, 16) == MW_LIVE_MATCH_MALFORMED);
    assert(output.result.abi_version == 1 && output.result.struct_bytes == 16 && output.result.status == MW_LIVE_MATCH_MALFORMED && !output.result.reserved);
    assert(mazewall_live_match_arguments_v1(buffers + 1, 128, &output.result, 16) == MW_LIVE_MATCH_MALFORMED);
    assert(mazewall_live_match_arguments_v1(buffers, 127, &output.result, 16) == MW_LIVE_MATCH_MALFORMED);
    check(input, MW_LIVE_MATCH_MATCH);
    w.run([] { leave(); }); check(input, MW_LIVE_MATCH_UNAVAILABLE);
    const Arguments at{ArgumentKind::Openat0, 0, 0x80000000U, 0xffffffffU, 0xffffff9cU};
    w.run([&] { enter(); mw_live_frame_v1 frame{i.token, -1}; publish(2, &frame, 1, Reason::None, at); });
    q.invocation_id = 0; const auto at_snapshot = read(q); q.invocation_id = at_snapshot.invocation_id;
    input.request = q; input.expected_publication = at_snapshot.publication_id; input.kind = 2;
    input.path_bits = at.path_bits; input.flags_bits = at.flags_bits; input.mode_bits = at.mode_bits; input.dirfd_bits = at.dirfd_bits;
    check(input, MW_LIVE_MATCH_MATCH);
    bad = input; bad.dirfd_bits ^= 1; check(bad, MW_LIVE_MATCH_UNAVAILABLE);
    bad = input; bad.expected_publication = snapshot.publication_id; check(bad, MW_LIVE_MATCH_UNAVAILABLE);
    auto cancel = i.operation(6); call(cancel); check(input, MW_LIVE_MATCH_UNAVAILABLE);
    w.run([] { leave(); }); check(input, MW_LIVE_MATCH_UNAVAILABLE);
    q.invocation_id = 0; end(w, q); retire(i, w, q);
    check(input, MW_LIVE_MATCH_UNAVAILABLE);
}

void exports(const char* path) {
    void* library = dlopen(path, RTLD_NOW | RTLD_LOCAL); assert(library);
    const char* names[] = {"mazewall_live_info_v1", "mazewall_live_lifecycle_v1", "mazewall_live_copy_v1", "mazewall_live_match_arguments_v1"};
    for (auto name : names) assert(dlsym(library, name));
    auto info = reinterpret_cast<uint32_t (*)(void*, uint32_t)>(dlsym(library, names[0]));
    mw_live_info_v1 out{}; assert(info(&out, 64) == 5 && out.status == 5 && out.reason == 19);
    const auto* p = reinterpret_cast<const unsigned char*>(&out);
    for (size_t n = 16; n < sizeof(out); ++n) assert(p[n] == 0);
    assert(!dlsym(library, "_ZN8mazewall4live10initializeERKNS0_7StartupE"));
    assert(!dlsym(library, "test_hook"));
    // Inspect defined dynamic symbols, not just a few guessed private mangled names.
    std::ifstream input(path, std::ios::binary);
    std::vector<char> bytes{std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
    assert(bytes.size() >= sizeof(Elf64_Ehdr));
    Elf64_Ehdr header{}; std::memcpy(&header, bytes.data(), sizeof(header));
    assert(std::memcmp(header.e_ident, ELFMAG, SELFMAG) == 0 && header.e_ident[EI_CLASS] == ELFCLASS64);
    auto section = [&](unsigned index) {
        auto offset = header.e_shoff + static_cast<size_t>(index) * header.e_shentsize;
        assert(index < header.e_shnum && offset + sizeof(Elf64_Shdr) <= bytes.size());
        Elf64_Shdr value{}; std::memcpy(&value, bytes.data() + offset, sizeof(value)); return value;
    };
    unsigned live_exports = 0;
    for (unsigned n = 0; n < header.e_shnum; ++n) {
        auto symbols = section(n); if (symbols.sh_type != SHT_DYNSYM) continue;
        auto strings = section(symbols.sh_link);
        assert(strings.sh_offset + strings.sh_size <= bytes.size());
        assert(symbols.sh_offset + symbols.sh_size <= bytes.size() && symbols.sh_entsize == sizeof(Elf64_Sym));
        for (size_t offset = 0; offset < symbols.sh_size; offset += sizeof(Elf64_Sym)) {
            Elf64_Sym symbol{}; std::memcpy(&symbol, bytes.data() + symbols.sh_offset + offset, sizeof(symbol));
            if (symbol.st_shndx == SHN_UNDEF) continue;
            assert(symbol.st_name < strings.sh_size);
            const auto* name = bytes.data() + strings.sh_offset + symbol.st_name;
            assert(std::memchr(name, 0, strings.sh_size - symbol.st_name));
            assert(!std::strstr(name, "mazewall4live") && !std::strstr(name, "test_hook"));
            if (std::strstr(name, "mazewall_live_")) {
                bool approved = false;
                for (auto required : names) approved |= std::strcmp(name, required) == 0;
                assert(approved); ++live_exports;
            }
        }
    }
    assert(live_exports == 4);
    dlclose(library);
}
}
int main(int argc, char** argv) {
    if (argc == 3 && std::strcmp(argv[1], "--exports") == 0) { exports(argv[2]); return 0; }
    if (argc == 2) {
        if (std::strcmp(argv[1], "runtime") == 0 || std::strcmp(argv[1], "binding") == 0 ||
            std::strcmp(argv[1], "helper") == 0 || std::strcmp(argv[1], "hooks") == 0) {
            readiness_failure(argv[1]); return 0;
        }
        assert(initialize({91, 37, static_cast<uint32_t>(getpid()), 3, true, true, true}));
        if (std::strncmp(argv[1], "probe-", 6) == 0) probe_eligibility(argv[1] + 6);
        else if (std::strcmp(argv[1], "shutdown") == 0) generation_and_cleanup(true);
        else if (std::strcmp(argv[1], "ids") == 0) isolated_exhaustion(false);
        else if (std::strcmp(argv[1], "serial") == 0) isolated_exhaustion(true);
        else if (std::strcmp(argv[1], "publish-shutdown") == 0) invalidation_interleavings(true);
        else if (std::strcmp(argv[1], "publish-generation") == 0) invalidation_interleavings(false);
        else if (std::strcmp(argv[1], "read-shutdown") == 0) read_invalidation(true);
        else if (std::strcmp(argv[1], "read-generation") == 0) read_invalidation(false);
        else assert(false);
        assert(owners.load() == 0); return 0;
    }
    available_while_original_stopped();
    argument_provenance();
    isolated_argument_lifecycle();
    argument_retirement_reuse();
    invalid_published_arguments_preserve_snapshot();
    native_match_wrapper();
    for (auto point : {TestPoint::MatchReleased, TestPoint::MatchLive, TestPoint::MatchControl,
                       TestPoint::MatchTask, TestPoint::MatchPublication}) argument_match_revocation(point);
    probe_eligibility();
    malformed_buffers(); bounds_and_enrollment(); lifecycle_and_capture(); held_gates();
    reader_reuse(TestPoint::CopyDiscovered); reader_reuse(TestPoint::CopyReleased);
    cancellation_and_commit_boundaries(); generation_and_cleanup(false);
    assert(owners.load() == 0);
    std::puts("live-stack-core: ABI/bounds/lifecycle/capture/I2/I3/R1/commit controls passed (synthetic enrollment)");
}
