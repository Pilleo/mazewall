#include "../src/agent.cpp"

#include <cassert>
#include <chrono>
#include <condition_variable>
#include <filesystem>
#include <iostream>
#include <iterator>
#include <limits>
#include <sys/wait.h>

namespace {
thread_local bool observe_allocations = false;
bool fail_allocation = false;
std::atomic<unsigned> allocations{0};
}
extern "C" void* __real__Znwm(std::size_t);
extern "C" void* __wrap__Znwm(std::size_t size) {
    if (observe_allocations) allocations.fetch_add(1, std::memory_order_relaxed);
    if (fail_allocation) throw std::bad_alloc();
    return __real__Znwm(size);
}

using namespace mazewall::profiler;

namespace {
std::mutex pause_mutex;
std::condition_variable pause_changed;
bool pause_enabled = false, owned = false, released = false, b_done = false;
thread_local bool pause_this_thread = false;
bool pause_precheck = false;

void wait_for(std::unique_lock<std::mutex>& lock, const bool& flag) {
    assert(pause_changed.wait_for(lock, std::chrono::seconds(10), [&] { return flag; }));
}

std::vector<std::uint8_t> read(const std::string& name) {
    std::ifstream stream(name, std::ios::binary);
    assert(stream.good());
    return {std::istreambuf_iterator<char>(stream), {}};
}

void check_rows(const std::string& name, unsigned count) {
    const auto bytes = read(name);
    assert(bytes.size() == 24 + count * 16);
    assert(bytes[0] == 'M' && bytes[1] == 'Z' && bytes[2] == 'C' && bytes[3] == 'Y');
    assert(CaptureYieldRecorder::read_u16_le(bytes, 4) == 1);
    assert(CaptureYieldRecorder::read_u16_le(bytes, 6) == 16);
    assert(CaptureYieldRecorder::read_u32_le(bytes, 8) == session_tag);
    assert(CaptureYieldRecorder::read_u32_le(bytes, 12) == 0);
    assert(CaptureYieldRecorder::read_u64_le(bytes, 16) == count);
    for (unsigned i = 0; i < count; ++i) {
        const auto offset = 24 + i * 16;
        assert(CaptureYieldRecorder::read_u64_le(bytes, offset) == i + 1);
        assert(CaptureYieldRecorder::read_u16_le(bytes, offset + 8) == (i == 0 ? 1 : 5));
        assert(CaptureYieldRecorder::read_u16_le(bytes, offset + 10) == (i == 0 ? 1 : 2));
        assert(CaptureYieldRecorder::read_u32_le(bytes, offset + 12) == 0);
    }
}
}

namespace mazewall::profiler {
struct CaptureYieldRecorderTestAccess {
    static std::uint64_t control(const CaptureYieldRecorder& r) {
        return r.control_.load(std::memory_order_relaxed);
    }
    static bool untouched(const CaptureYieldRecorder& r, std::size_t i) {
        const auto& s = r.records_[i];
        return s.commit.load(std::memory_order_acquire) == 0 && s.invocation_id == 0 && s.binding_kind == 0;
    }
    static void seed_last_slot(CaptureYieldRecorder& r) {
        assert(r.capacity_ == 2 && control(r) == 0);
        r.records_[0].invocation_id = 1;
        r.records_[0].binding_kind = 1;
        r.records_[0].outcome = CaptureYieldOutcome::captured;
        r.records_[0].commit.store(1, std::memory_order_release);
        r.control_.store(1, std::memory_order_relaxed);
    }
};
}

namespace {
void checkpoint(bool precheck) {
    errno = EILSEQ;
    if (pause_precheck != precheck) return;
    if (!pause_this_thread || !pause_enabled) return;
    std::unique_lock lock(pause_mutex);
    owned = true;
    pause_changed.notify_all();
    wait_for(lock, released);
}
void record(unsigned id = 1) {
    errno = EDOM;
    observe_allocations = true;
    record_capture_yield(id, id == 1 ? BindingKind::unix_open0 : BindingKind::file_read0,
                         id == 1 ? CaptureYieldOutcome::captured : CaptureYieldOutcome::capture_failed);
    observe_allocations = false;
    assert(errno == EDOM);
}
void resume() {
    std::lock_guard lock(pause_mutex);
    released = true;
    pause_changed.notify_all();
}
void no_files() {
    assert(!std::filesystem::exists(capture_yield_path));
    assert(!std::filesystem::exists(capture_yield_path + ".tmp"));
    assert(capture_yield_test_rename_attempts == 0);
}
}
void mazewall::profiler::capture_yield_test::ownership_checkpoint() { checkpoint(false); }
void mazewall::profiler::capture_yield_test::precheck_checkpoint() { checkpoint(true); }

int main(int argc, char** argv) {
    assert(argc == 2 || argc == 3);
    char path[] = "/tmp/opencode/a4-yield-002-fixture-XXXXXX";
    const char* directory = ::mkdtemp(path);
    assert(directory != nullptr);
    std::cerr << "retained-fixture=" << directory << '\n';
    capture_yield_path = std::string(directory) + "/capture.mzcy";
    session_tag = 0x1234;
    const std::string mode = argv[1];
    if (mode == "disabled") {
        record();
        record(0);
        no_files();
        assert(allocations.load(std::memory_order_relaxed) == 0);
        return 0;
    }
    capture_yield_recorder = std::make_unique<CaptureYieldRecorder>(mode == "full" ? 65'536 : mode == "zero" ? 0 : 2);
    record(0);
    assert(CaptureYieldRecorderTestAccess::control(*capture_yield_recorder) == 0);
    assert(capture_yield_recorder->dropped_count() == 0);
    if (mode == "full") {
        assert(argc == 3);
        for (unsigned i = 1; i <= 65'536; ++i) record(i);
        assert(capture_yield_recorder->dropped_count() == 0 && write_capture_yield());
        check_rows(capture_yield_path, 65'536);
        const auto child = ::fork();
        assert(child >= 0);
        if (child == 0) {
            ::execl(argv[2], argv[2], capture_yield_path.c_str(), static_cast<char*>(nullptr));
            ::_exit(127);
        }
        int status = 0;
        assert(::waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    } else if (mode == "zero") {
        record();
        assert(CaptureYieldRecorderTestAccess::control(*capture_yield_recorder) == 0);
        assert(capture_yield_recorder->dropped_count() == UINT64_MAX);
        assert(!write_capture_yield());
        no_files();
    } else if (mode == "overlap" || mode == "hole" || mode == "stale-close" || mode == "stale-full") {
        pause_enabled = true;
        pause_precheck = mode == "stale-close" || mode == "stale-full";
        if (mode == "stale-full") CaptureYieldRecorderTestAccess::seed_last_slot(*capture_yield_recorder);
        std::thread a([] {
            pause_this_thread = true;
            record(1);
        });
        {
            std::unique_lock lock(pause_mutex);
            wait_for(lock, owned);
        }
        std::thread b([] {
            record(2);
            std::lock_guard lock(pause_mutex);
            b_done = true;
            pause_changed.notify_all();
        });
        {
            std::unique_lock lock(pause_mutex);
            wait_for(lock, b_done);
        }
        b.join();
        if (mode == "hole") {
            assert(!write_capture_yield());
            no_files();
        } else if (mode == "stale-close") {
            assert(write_capture_yield());
            const auto bytes = read(capture_yield_path);
            assert(CaptureYieldRecorder::read_u64_le(bytes, 16) == 1);
            assert(CaptureYieldRecorder::read_u64_le(bytes, 24) == 2);
        }
        resume();
        a.join();
        if (mode == "overlap") {
            assert(capture_yield_recorder->dropped_count() == 0);
            assert(write_capture_yield());
            check_rows(capture_yield_path, 2);
            assert(CaptureYieldRecorderTestAccess::control(*capture_yield_recorder) == (1ULL << 63) + 2);
        } else if (mode == "hole") {
            assert(capture_yield_recorder->record_count() == 2);
            assert(!write_capture_yield());
            no_files();
        } else {
            assert(capture_yield_recorder->dropped_count() == UINT64_MAX);
            if (mode == "stale-close") {
                assert(CaptureYieldRecorderTestAccess::untouched(*capture_yield_recorder, 1));
                assert(!write_capture_yield());
            } else {
                assert(CaptureYieldRecorderTestAccess::control(*capture_yield_recorder) == 3);
                assert(!write_capture_yield());
                no_files();
            }
        }
    } else if (mode == "close-error" || mode == "clean" || mode == "unload") {
        record();
        capture_yield_test_close_error = mode != "clean";
        if (mode == "unload") {
            live_startup_mode = LiveStartupMode::DiagnosticOnly;
            Agent_OnUnload(nullptr);
        } else {
            const bool result = write_capture_yield();
            std::cerr << "writer-result=" << result << " rename-attempts=" << capture_yield_test_rename_attempts << '\n';
            assert(result == (mode == "clean"));
        }
        assert(capture_yield_test_rename_attempts == (mode == "clean" ? 1U : 0U));
        assert(std::filesystem::exists(capture_yield_path) == (mode == "clean"));
        assert(std::filesystem::exists(capture_yield_path + ".tmp") == (mode != "clean"));
        check_rows(capture_yield_path + (mode == "clean" ? "" : ".tmp"), 1);
    } else if (mode == "overflow" || mode == "errno" || mode == "last-slot") {
        if (mode == "last-slot") CaptureYieldRecorderTestAccess::seed_last_slot(*capture_yield_recorder);
        else record();
        record(2);
        if (mode == "last-slot") {
            assert(write_capture_yield());
            check_rows(capture_yield_path, 2);
        } else {
            record(3);
            const auto before = CaptureYieldRecorderTestAccess::control(*capture_yield_recorder);
            for (int i = 0; i < 100; ++i) record(3);
            record(0);
            assert(CaptureYieldRecorderTestAccess::control(*capture_yield_recorder) == before);
            assert(before == 2 && capture_yield_recorder->dropped_count() == UINT64_MAX);
            assert(!write_capture_yield());
            no_files();
            const auto sealed = CaptureYieldRecorderTestAccess::control(*capture_yield_recorder);
            record(4);
            assert(CaptureYieldRecorderTestAccess::control(*capture_yield_recorder) == sealed);
            assert(!write_capture_yield());
        }
    } else if (mode == "open-error" || mode == "flush-error" || mode == "rename-error" || mode == "allocation-error") {
        record();
        if (mode == "open-error") capture_yield_path += "/missing/sidecar";
        if (mode == "flush-error") assert(::symlink("/dev/full", (capture_yield_path + ".tmp").c_str()) == 0);
        if (mode == "rename-error") {
            assert(std::filesystem::create_directory(capture_yield_path));
            std::ofstream(capture_yield_path + "/owned-child") << "retained\n";
        }
        fail_allocation = mode == "allocation-error";
        const bool result = write_capture_yield();
        fail_allocation = false;
        assert(!result);
        assert(capture_yield_test_rename_attempts == (mode == "rename-error" ? 1U : 0U));
        if (mode == "rename-error") check_rows(capture_yield_path + ".tmp", 1);
        if (mode == "flush-error") assert(std::filesystem::is_symlink(capture_yield_path + ".tmp"));
        if (mode == "allocation-error" || mode == "open-error") no_files();
        assert(!write_capture_yield());
        assert(capture_yield_test_rename_attempts == (mode == "rename-error" ? 1U : 0U));
    } else {
        assert(false);
    }
    assert(allocations.load(std::memory_order_relaxed) == 0);
}
