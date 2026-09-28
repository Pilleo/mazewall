#pragma once

#include <cassert>
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <vector>

namespace mazewall::profiler {

/** Opt-in native witness, written at agent unload; never replaces the target-authored FD manifest. */
class InvocationFdRecorder {
public:
    static constexpr std::size_t header_size = 32;
    static constexpr std::size_t record_size = 16;

    explicit InvocationFdRecorder(std::size_t capacity) : capacity_(capacity) {
        if (capacity == 0) throw std::invalid_argument("invocation-FD witness capacity must be positive");
        records_.reserve(capacity);
    }

    [[nodiscard]] bool record(std::uint64_t invocation_id, std::int32_t directory_fd) {
        if (invocation_id == 0 || directory_fd < 0 || records_.size() >= capacity_) {
            ++dropped_count_;
            return false;
        }
        records_.push_back({invocation_id, directory_fd});
        return true;
    }

    [[nodiscard]] std::size_t dropped_count() const { return dropped_count_; }

    [[nodiscard]] std::vector<std::uint8_t> serialize(std::uint32_t session_tag) const {
        std::vector<std::uint8_t> bytes(header_size + records_.size() * record_size, 0);
        bytes[0] = 'M'; bytes[1] = 'Z'; bytes[2] = 'I'; bytes[3] = 'F';
        append_u16_le(bytes, 4, 1);
        append_u16_le(bytes, 6, record_size);
        append_u32_le(bytes, 8, session_tag);
        append_u64_le(bytes, 16, records_.size());
        append_u64_le(bytes, 24, dropped_count_);
        for (std::size_t i = 0; i < records_.size(); ++i) {
            const auto offset = header_size + i * record_size;
            append_u64_le(bytes, offset, records_[i].invocation_id);
            append_u32_le(bytes, offset + 8, static_cast<std::uint32_t>(records_[i].directory_fd));
        }
        return bytes;
    }

    [[nodiscard]] static std::uint16_t read_u16_le(const std::vector<std::uint8_t>& bytes, std::size_t offset) {
        assert(offset + 2 <= bytes.size());
        return static_cast<std::uint16_t>(bytes[offset]) | static_cast<std::uint16_t>(bytes[offset + 1]) << 8;
    }

    [[nodiscard]] static std::uint32_t read_u32_le(const std::vector<std::uint8_t>& bytes, std::size_t offset) {
        assert(offset + 4 <= bytes.size());
        std::uint32_t value = 0;
        for (std::size_t i = 0; i < 4; ++i) value |= static_cast<std::uint32_t>(bytes[offset + i]) << (i * 8);
        return value;
    }

    [[nodiscard]] static std::uint64_t read_u64_le(const std::vector<std::uint8_t>& bytes, std::size_t offset) {
        assert(offset + 8 <= bytes.size());
        std::uint64_t value = 0;
        for (std::size_t i = 0; i < 8; ++i) value |= static_cast<std::uint64_t>(bytes[offset + i]) << (i * 8);
        return value;
    }

private:
    struct Record { std::uint64_t invocation_id; std::int32_t directory_fd; };

    static void append_u16_le(std::vector<std::uint8_t>& bytes, std::size_t offset, std::uint16_t value) {
        for (std::size_t i = 0; i < 2; ++i) bytes[offset + i] = static_cast<std::uint8_t>(value >> (i * 8));
    }

    static void append_u32_le(std::vector<std::uint8_t>& bytes, std::size_t offset, std::uint32_t value) {
        for (std::size_t i = 0; i < 4; ++i) bytes[offset + i] = static_cast<std::uint8_t>(value >> (i * 8));
    }

    static void append_u64_le(std::vector<std::uint8_t>& bytes, std::size_t offset, std::uint64_t value) {
        for (std::size_t i = 0; i < 8; ++i) bytes[offset + i] = static_cast<std::uint8_t>(value >> (i * 8));
    }

    std::size_t capacity_;
    std::size_t dropped_count_ = 0;
    std::vector<Record> records_;
};

} // namespace mazewall::profiler
