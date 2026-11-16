#pragma once

#include "binding_manifest.hpp"

#include <cassert>
#include <algorithm>
#include <atomic>
#include <climits>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <vector>

namespace mazewall::profiler {

#ifdef MAZEWALL_CAPTURE_YIELD_TEST
namespace capture_yield_test {
void ownership_checkpoint();
void precheck_checkpoint();
}
#endif

enum class CaptureYieldOutcome : std::uint16_t {
    captured = 1,
    capture_failed = 2,
    admission_failed = 3,
    excluded = 4,
};

class CaptureYieldRecorder {
public:
    static constexpr std::size_t header_size = 24;
    static constexpr std::size_t record_size = 16;

    explicit CaptureYieldRecorder(std::size_t capacity)
        : capacity_(checked_capacity(capacity)), records_(capacity_) {}

    [[nodiscard]] bool record(
        std::uint64_t invocation_id,
        BindingKind binding_kind,
        CaptureYieldOutcome outcome) noexcept {
        // Linux positive signed-int TIDs, one non-reentrant call per thread: stale
        // entrants after full/CLOSED are bounded by INT_MAX, not lifetime calls.
        const auto observed = control_.fetch_add(0, std::memory_order_relaxed);
        if ((observed & closed) != 0 || observed >= capacity_) return reject();
#ifdef MAZEWALL_CAPTURE_YIELD_TEST
        capture_yield_test::precheck_checkpoint();
#endif
        const auto ticket = control_.fetch_add(1, std::memory_order_relaxed);
        if ((ticket & closed) != 0 || ticket >= capacity_) return reject();
#ifdef MAZEWALL_CAPTURE_YIELD_TEST
        capture_yield_test::ownership_checkpoint();
#endif
        auto& slot = records_[ticket];
        slot.invocation_id = invocation_id;
        slot.binding_kind = binding_code(binding_kind);
        slot.outcome = outcome;
        slot.commit.store(1, std::memory_order_release);
        return true;
    }

    [[nodiscard]] std::size_t record_count() const {
        return std::min<std::uint64_t>(control_.load(std::memory_order_relaxed) & ~closed, capacity_);
    }

    [[nodiscard]] std::uint64_t dropped_count() const {
        return dropped_count_.load(std::memory_order_relaxed);
    }

    [[nodiscard]] std::vector<std::uint8_t> serialize(std::uint32_t session_tag) {
        // CLOSED is also the terminal publication latch: even failed I/O cannot
        // retry a clean snapshot. This cut does not join producers or permit unload.
        const auto cutoff = control_.fetch_or(closed, std::memory_order_acq_rel);
        if ((cutoff & closed) != 0 || dropped_count() != 0) {
            throw std::runtime_error("capture-yield closed or lost records");
        }
        const auto count = std::min<std::uint64_t>(cutoff, capacity_);
        for (std::size_t index = 0; index < count; ++index) {
            if (records_[index].commit.load(std::memory_order_acquire) != 1) {
                throw std::runtime_error("capture-yield uncommitted reservation");
            }
        }
        std::vector<std::uint8_t> bytes(header_size + count * record_size, 0);
        bytes[0] = 'M';
        bytes[1] = 'Z';
        bytes[2] = 'C';
        bytes[3] = 'Y';
        append_u16_le(bytes, 4, 1);
        append_u16_le(bytes, 6, record_size);
        append_u32_le(bytes, 8, session_tag);
        append_u64_le(bytes, 16, count);
        for (std::size_t index = 0; index < count; ++index) {
            const std::size_t offset = header_size + index * record_size;
            const Slot& record = records_[index];
            append_u64_le(bytes, offset, record.invocation_id);
            append_u16_le(bytes, offset + 8, record.binding_kind);
            append_u16_le(bytes, offset + 10, static_cast<std::uint16_t>(record.outcome));
        }
        return bytes;
    }

    [[nodiscard]] static constexpr std::uint16_t binding_code(BindingKind binding_kind) {
        switch (binding_kind) {
        case BindingKind::unsupported:
            return 0;
        case BindingKind::unix_open0:
            return 1;
        case BindingKind::unix_openat0:
            return 2;
        case BindingKind::unix_close0:
            return 3;
        case BindingKind::unix_unlink0:
            return 4;
        case BindingKind::file_read0:
            return 5;
        case BindingKind::file_write0:
            return 6;
        case BindingKind::file_close_int_fd:
            return 7;
        case BindingKind::socket_read0:
            return 8;
        case BindingKind::socket_write0:
            return 9;
        case BindingKind::net_socket0:
            return 10;
        case BindingKind::net_connect0:
            return 11;
        case BindingKind::net_accept:
            return 12;
        }
        return 0;
    }

    [[nodiscard]] static std::uint16_t read_u16_le(const std::vector<std::uint8_t>& bytes, std::size_t offset) {
        assert(offset + 2 <= bytes.size());
        return static_cast<std::uint16_t>(bytes[offset]) |
            (static_cast<std::uint16_t>(bytes[offset + 1]) << 8);
    }

    [[nodiscard]] static std::uint32_t read_u32_le(const std::vector<std::uint8_t>& bytes, std::size_t offset) {
        assert(offset + 4 <= bytes.size());
        std::uint32_t value = 0;
        for (std::size_t index = 0; index < 4; ++index) {
            value |= static_cast<std::uint32_t>(bytes[offset + index]) << (index * 8);
        }
        return value;
    }

    [[nodiscard]] static std::uint64_t read_u64_le(const std::vector<std::uint8_t>& bytes, std::size_t offset) {
        assert(offset + 8 <= bytes.size());
        std::uint64_t value = 0;
        for (std::size_t index = 0; index < 8; ++index) {
            value |= static_cast<std::uint64_t>(bytes[offset + index]) << (index * 8);
        }
        return value;
    }

private:
    struct Slot {
        std::uint64_t invocation_id = 0;
        std::uint16_t binding_kind = 0;
        CaptureYieldOutcome outcome{};
        std::atomic<std::uint32_t> commit{0};
    };
    static_assert(sizeof(Slot) == 16);
    static_assert(CHAR_BIT == 8 && INT_MAX == 2'147'483'647);
    static_assert(std::atomic<std::uint64_t>::is_always_lock_free);
    static_assert(std::atomic<std::uint32_t>::is_always_lock_free);
    static_assert(65'536ULL + INT_MAX < (1ULL << 32));
    static constexpr std::uint64_t closed = 1ULL << 63;

#ifdef MAZEWALL_CAPTURE_YIELD_TEST
    friend struct CaptureYieldRecorderTestAccess;
#endif

    static std::size_t checked_capacity(std::size_t capacity) {
        if (capacity > 65'536) throw std::invalid_argument("capture-yield capacity exceeds 65536");
        return capacity;
    }

    bool reject() noexcept {
        dropped_count_.store(std::numeric_limits<std::uint64_t>::max(), std::memory_order_relaxed);
        return false;
    }

    static void append_u16_le(std::vector<std::uint8_t>& bytes, std::size_t offset, std::uint16_t value) {
        bytes[offset] = static_cast<std::uint8_t>(value);
        bytes[offset + 1] = static_cast<std::uint8_t>(value >> 8);
    }

    static void append_u32_le(std::vector<std::uint8_t>& bytes, std::size_t offset, std::uint32_t value) {
        for (std::size_t index = 0; index < 4; ++index) {
            bytes[offset + index] = static_cast<std::uint8_t>(value >> (index * 8));
        }
    }

    static void append_u64_le(std::vector<std::uint8_t>& bytes, std::size_t offset, std::uint64_t value) {
        for (std::size_t index = 0; index < 8; ++index) {
            bytes[offset + index] = static_cast<std::uint8_t>(value >> (index * 8));
        }
    }

    std::size_t capacity_;
    std::atomic<std::uint64_t> dropped_count_{0};
    std::atomic<std::uint64_t> control_{0};
    std::vector<Slot> records_;
};

}  // namespace mazewall::profiler
