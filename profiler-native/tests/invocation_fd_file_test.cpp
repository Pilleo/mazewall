#include "invocation_fd_recorder.hpp"

#include <cassert>
#include <cstdint>
#include <fstream>
#include <iterator>
#include <set>
#include <vector>

int main(int argc, char** argv) {
    assert(argc == 2);
    std::ifstream source(argv[1], std::ios::binary);
    assert(source.good());
    const std::vector<std::uint8_t> bytes{std::istreambuf_iterator<char>(source), std::istreambuf_iterator<char>()};
    using mazewall::profiler::InvocationFdRecorder;
    assert(bytes.size() >= InvocationFdRecorder::header_size);
    assert(bytes[0] == 'M' && bytes[1] == 'Z' && bytes[2] == 'I' && bytes[3] == 'F');
    assert(InvocationFdRecorder::read_u16_le(bytes, 4) == 1);
    assert(InvocationFdRecorder::read_u16_le(bytes, 6) == InvocationFdRecorder::record_size);
    assert(InvocationFdRecorder::read_u32_le(bytes, 8) == 0x4d5a);
    const auto count = InvocationFdRecorder::read_u64_le(bytes, 16);
    assert(count >= 1 && count <= 65'536);
    assert(InvocationFdRecorder::read_u64_le(bytes, 24) == 0);
    assert(bytes.size() == InvocationFdRecorder::header_size + count * InvocationFdRecorder::record_size);
    std::set<std::uint64_t> ids;
    for (std::uint64_t row = 0; row < count; ++row) {
        const auto offset = InvocationFdRecorder::header_size + row * InvocationFdRecorder::record_size;
        const auto id = InvocationFdRecorder::read_u64_le(bytes, offset);
        assert(id >> 32 == 0x4d5a);
        assert(ids.insert(id).second);
        assert(InvocationFdRecorder::read_u32_le(bytes, offset + 8) > 0);
    }
}
