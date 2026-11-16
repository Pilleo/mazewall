#pragma once

#include <array>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <linux/fs.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <sys/syscall.h>
#include <unistd.h>

#pragma GCC visibility push(hidden)
namespace mazewall::profiler {

struct OpenBoundaryInput {
    std::array<char, 256> path{};
    std::uint32_t namespace_pid = 0, namespace_tid = 0, failures = 0, path_length = 0;
    std::uint64_t bridge_start = 0, bridge_end = 0, path_pointer = 0, ordinal = 0;
    std::int32_t flags = 0, mode = 0;
};

struct OpenBoundaryToken {
    static constexpr std::uint32_t invalid_slot = UINT32_MAX;
    std::uint32_t slot = invalid_slot;
    std::uint64_t invocation_id = 0;
};

struct OpenBoundaryBridge {
    std::uint64_t start = 0, end = 0, ordinal = 0;
    std::uint32_t tid = 0, failures = 0;
    bool initialized = false;
};
static_assert(sizeof(OpenBoundaryBridge) <= 40);
static_assert(std::atomic<std::uint32_t>::is_always_lock_free && std::atomic<std::uint64_t>::is_always_lock_free);

class OpenBoundaryRecorder {
public:
    using Clock = bool (*)(std::uint64_t&) noexcept;
    using Tid = std::int64_t (*)() noexcept;
    using PathReader = bool (*)(std::uint64_t, char*, std::uint32_t&) noexcept;
    static constexpr std::uint32_t production_capacity = 1024;
    static constexpr std::uint32_t bridge_capacity = 256;
    OpenBoundaryRecorder(std::uint32_t capacity, Clock clock, Tid tid, PathReader reader)
        : capacity_(capacity), clock_(clock), tid_(tid), reader_(reader), pid_(::getpid()) {
        if (capacity == 0 || capacity > production_capacity || !clock || !tid || !reader)
            throw std::invalid_argument("invalid open-boundary recorder bounds or readers");
        slots_ = std::make_unique<Slot[]>(capacity);
    }

    OpenBoundaryBridge* reserve_bridge() noexcept {
        auto index = bridges_reserved_.load(std::memory_order_relaxed);
        while (index < bridge_capacity) {
            if (bridges_reserved_.compare_exchange_weak(index, index + 1, std::memory_order_relaxed))
                return &bridges_[index];
        }
        return nullptr;
    }

    OpenBoundaryInput prepare(OpenBoundaryBridge& bridge, std::uint64_t pointer,
                              std::int32_t flags, std::int32_t mode) noexcept {
        const int saved_errno = errno;
        if (!bridge.initialized) {
            bridge.initialized = true;
            const bool start_valid = clock_(bridge.start);
            const auto tid = tid_();
            const bool end_valid = clock_(bridge.end);
            if (!start_valid || !end_valid || bridge.start == 0 || bridge.end < bridge.start)
                bridge.failures |= clock_failure;
            if (tid <= 0 || tid > UINT32_MAX) bridge.failures |= identity_failure;
            else bridge.tid = static_cast<std::uint32_t>(tid);
        }
        OpenBoundaryInput input;
        input.namespace_pid = pid_;
        input.namespace_tid = bridge.tid;
        input.bridge_start = bridge.start;
        input.bridge_end = bridge.end;
        input.failures = bridge.failures;
        input.ordinal = ++bridge.ordinal;
        if (input.ordinal == 0) input.failures |= identity_failure;
        input.path_pointer = pointer;
        input.flags = flags;
        input.mode = mode;
        if (!reader_(pointer, input.path.data(), input.path_length) || input.path_length == 0 ||
            input.path_length > input.path.size() || input.path[input.path_length - 1] != '\0') {
            input.failures |= input_failure;
            input.path_length = 0;
            input.path.fill(0);
        }
        errno = saved_errno;
        return input;
    }

    OpenBoundaryToken begin(const OpenBoundaryInput& input, std::uint64_t id,
                            std::uint64_t execution_id, bool original_present) noexcept {
        attempted_.fetch_add(1, std::memory_order_relaxed);
        const auto index = reserved_.fetch_add(1, std::memory_order_relaxed);
        if (index >= capacity_) {
            overflow_.fetch_add(1, std::memory_order_relaxed);
            return {};
        }
        auto& slot = slots_[index];
        auto failures = input.failures;
        std::uint64_t time = 0;
        if (!clock_(time) || time == 0 || time < input.bridge_end) failures |= clock_failure;
        if (id == 0 || execution_id == 0 || input.namespace_pid != pid_ || input.namespace_tid == 0 || input.ordinal == 0)
            failures |= identity_failure;
        if (!original_present) failures |= original_absent;
        slot.put(4, failures, 4);
        slot.put(8, id);
        slot.put(16, input.ordinal);
        slot.put(24, input.namespace_pid, 4);
        slot.put(28, input.namespace_tid, 4);
        slot.put(32, input.bridge_start);
        slot.put(40, input.bridge_end);
        slot.put(48, time);
        slot.put(64, input.path_pointer);
        slot.put(72, static_cast<std::uint32_t>(input.flags), 4);
        slot.put(76, static_cast<std::uint32_t>(input.mode), 4);
        slot.put(84, input.path_length, 4);
        slot.put(88, original_present ? 1 : 0, 4);
        std::memcpy(slot.fields.data() + 92, input.path.data(), input.path.size());
        slot.put(352, execution_id);
        if (failures & clock_failure) clock_failures_.fetch_add(1, std::memory_order_relaxed);
        if (failures & input_failure) input_failures_.fetch_add(1, std::memory_order_relaxed);
        if (failures & identity_failure) identity_failures_.fetch_add(1, std::memory_order_relaxed);
        slot.commit.store(1, std::memory_order_release);
        entered_.fetch_add(1, std::memory_order_relaxed);
        return {static_cast<std::uint32_t>(index), id};
    }

    void finish(OpenBoundaryToken token, std::int32_t result) noexcept {
        if (token.slot >= capacity_) return;
        auto& slot = slots_[token.slot];
        if (slot.commit.load(std::memory_order_acquire) != 1 || slot.get(8) != token.invocation_id) return;
        std::uint64_t time = 0;
        if (!clock_(time) || time == 0 || time < slot.get(48)) {
            const auto failures = slot.get(4, 4);
            if (!(failures & clock_failure)) clock_failures_.fetch_add(1, std::memory_order_relaxed);
            slot.put(4, failures | clock_failure, 4);
        }
        slot.put(56, time);
        slot.put(80, static_cast<std::uint32_t>(result), 4);
        slot.commit.store(3, std::memory_order_release);
        returned_.fetch_add(1, std::memory_order_relaxed);
    }

    // Caller has stopped producers; acquire commits also retain entry-only and reserved slots.
    bool write(const std::string& destination, std::uint32_t session, bool producer_closed) noexcept {
        int fd = -1;
        try {
            if (destination.empty() || destination.front() != '/') { errno = EINVAL; return false; }
            const auto temporary = destination + ".tmp";
            fd = ::open(temporary.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
            if (fd < 0) return false;
            std::array<unsigned char, 384> bytes{};
            std::memcpy(bytes.data(), "MZOB", 4);
            put(bytes.data(), 4, 1, 2); put(bytes.data(), 6, 128, 2);
            put(bytes.data(), 8, 384, 4); put(bytes.data(), 12, capacity_, 4);
            put(bytes.data(), 16, session);
            put(bytes.data(), 24, attempted_.load(std::memory_order_relaxed));
            const auto reserved = reserved_.load(std::memory_order_relaxed);
            put(bytes.data(), 32, reserved);
            put(bytes.data(), 40, entered_.load(std::memory_order_relaxed));
            put(bytes.data(), 48, returned_.load(std::memory_order_relaxed));
            put(bytes.data(), 56, overflow_.load(std::memory_order_relaxed));
            put(bytes.data(), 64, clock_failures_.load(std::memory_order_relaxed));
            put(bytes.data(), 72, input_failures_.load(std::memory_order_relaxed));
            put(bytes.data(), 80, identity_failures_.load(std::memory_order_relaxed));
            put(bytes.data(), 88, producer_closed ? 1 : 0);
            put(bytes.data(), 96, pid_);
            bool ok = write_bytes(fd, bytes.data(), 128);
            for (std::uint64_t i = 0; ok && i < reserved && i < capacity_; ++i) {
                bytes.fill(0);
                const auto commit = slots_[i].commit.load(std::memory_order_acquire);
                put(bytes.data(), 0, commit, 4);
                if (commit != 0) std::memcpy(bytes.data() + 4, slots_[i].fields.data(), 380);
                ok = write_bytes(fd, bytes.data(), bytes.size());
            }
            if (ok && ::fsync(fd) != 0) ok = false;
            const int write_errno = errno;
            if (::close(fd) != 0 && ok) { fd = -1; return false; }
            fd = -1;
            if (!ok) { errno = write_errno; return false; }
            return ::syscall(SYS_renameat2, AT_FDCWD, temporary.c_str(), AT_FDCWD,
                             destination.c_str(), RENAME_NOREPLACE) == 0;
        } catch (...) {
            if (fd >= 0) (void)::close(fd);
            errno = ENOMEM;
            return false;
        }
    }
private:
    static constexpr std::uint32_t clock_failure = 1, input_failure = 2, identity_failure = 4, original_absent = 8;
    static void put(unsigned char* bytes, std::size_t offset, std::uint64_t value, int width = 8) noexcept {
        for (int i = 0; i < width; ++i) bytes[offset + i] = static_cast<unsigned char>(value >> (8 * i));
    }
    struct Slot {
        std::atomic<std::uint32_t> commit{0};
        std::array<unsigned char, 380> fields{};
        void put(std::size_t offset, std::uint64_t value, int width = 8) noexcept {
            OpenBoundaryRecorder::put(fields.data(), offset - 4, value, width);
        }
        std::uint64_t get(std::size_t offset, int width = 8) const noexcept {
            std::uint64_t value = 0;
            for (int i = 0; i < width; ++i) value |= std::uint64_t(fields[offset - 4 + i]) << (8 * i);
            return value;
        }
    };
    static_assert(sizeof(Slot) == 384);
    static bool write_bytes(int fd, const unsigned char* bytes, std::size_t size) noexcept {
        while (size > 0) {
            const auto count = ::write(fd, bytes, size);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) { if (count == 0) errno = EIO; return false; }
            bytes += count;
            size -= static_cast<std::size_t>(count);
        }
        return true;
    }
    std::uint32_t capacity_;
    Clock clock_;
    Tid tid_;
    PathReader reader_;
    std::uint32_t pid_;
    std::unique_ptr<Slot[]> slots_;
    std::atomic<std::uint64_t> attempted_{0}, reserved_{0}, entered_{0}, returned_{0}, overflow_{0};
    std::atomic<std::uint64_t> clock_failures_{0}, input_failures_{0}, identity_failures_{0};
    std::array<OpenBoundaryBridge, bridge_capacity> bridges_{};
    std::atomic<std::uint32_t> bridges_reserved_{0};
};
static_assert(sizeof(OpenBoundaryRecorder) <= 256 + 256 * sizeof(OpenBoundaryBridge));

template <typename Callable>
std::int32_t invoke_open_boundary(OpenBoundaryRecorder* recorder, const OpenBoundaryInput& input,
                                 std::uint64_t id, std::uint64_t execution_id,
                                 bool original_present, Callable&& callable) {
    if (recorder == nullptr) return callable();
    const int incoming_errno = errno;
    const auto token = recorder->begin(input, id, execution_id, original_present);
    errno = incoming_errno;
    const auto result = callable();
    const int original_errno = errno;
    recorder->finish(token, result);
    errno = original_errno;
    return result;
}

} // namespace mazewall::profiler
#pragma GCC visibility pop
