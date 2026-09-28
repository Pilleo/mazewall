#include "invocation_fd_recorder.hpp"

#include <cassert>
#include <cstdint>

int main() {
    using mazewall::profiler::InvocationFdRecorder;

    InvocationFdRecorder recorder(2);
    constexpr std::uint64_t first = 0x4d5a0000002aULL;
    constexpr std::uint64_t second = 0x4d5a0000002bULL;
    assert(recorder.record(first, 13));
    assert(recorder.record(second, 14));
    assert(!recorder.record(0x4d5a0000002cULL, 15));
    assert(recorder.dropped_count() == 1);

    const auto bytes = recorder.serialize(0x4d5a);
    assert(bytes.size() == 32 + 2 * 16);
    assert(bytes[0] == 'M' && bytes[1] == 'Z' && bytes[2] == 'I' && bytes[3] == 'F');
    assert(InvocationFdRecorder::read_u16_le(bytes, 4) == 1);
    assert(InvocationFdRecorder::read_u16_le(bytes, 6) == 16);
    assert(InvocationFdRecorder::read_u32_le(bytes, 8) == 0x4d5a);
    assert(InvocationFdRecorder::read_u64_le(bytes, 16) == 2);
    assert(InvocationFdRecorder::read_u64_le(bytes, 24) == 1);
    assert(InvocationFdRecorder::read_u64_le(bytes, 32) == first);
    assert(InvocationFdRecorder::read_u32_le(bytes, 40) == 13);
    assert(InvocationFdRecorder::read_u64_le(bytes, 48) == second);
    assert(InvocationFdRecorder::read_u32_le(bytes, 56) == 14);
}
