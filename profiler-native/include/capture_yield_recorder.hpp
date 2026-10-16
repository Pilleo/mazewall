#pragma once

#include "binding_manifest.hpp"

#include <cassert>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace mazewall::profiler {

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
        : capacity_(capacity) {
        records_.reserve(capacity_);
    }

    [[nodiscard]] bool record(
        std::uint64_t invocation_id,
        BindingKind binding_kind,
        CaptureYieldOutcome outcome) {
        if (records_.size() == capacity_) {
            ++dropped_count_;
            return false;
        }
        records_.push_back(Record{invocation_id, binding_code(binding_kind), outcome});
        return true;
    }

    [[nodiscard]] std::size_t record_count() const {
        return records_.size();
    }

    [[nodiscard]] std::size_t dropped_count() const {
        return dropped_count_;
    }

    [[nodiscard]] std::vector<std::uint8_t> serialize(std::uint32_t session_tag) const {
        std::vector<std::uint8_t> bytes(header_size + records_.size() * record_size, 0);
        bytes[0] = 'M';
        bytes[1] = 'Z';
        bytes[2] = 'C';
        bytes[3] = 'Y';
        append_u16_le(bytes, 4, 1);
        append_u16_le(bytes, 6, record_size);
        append_u32_le(bytes, 8, session_tag);
        append_u64_le(bytes, 16, records_.size());
        for (std::size_t index = 0; index < records_.size(); ++index) {
            const std::size_t offset = header_size + index * record_size;
            const Record& record = records_[index];
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
    struct Record {
        std::uint64_t invocation_id;
        std::uint16_t binding_kind;
        CaptureYieldOutcome outcome;
    };

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
    std::size_t dropped_count_ = 0;
    std::vector<Record> records_;
};

}  // namespace mazewall::profiler
