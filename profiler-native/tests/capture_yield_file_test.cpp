#include "capture_yield_recorder.hpp"

#include <cassert>
#include <cstdint>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

int main(int argc, char** argv) {
    assert(argc == 2);
    std::ifstream stream(argv[1], std::ios::binary);
    assert(stream.good());
    const std::vector<std::uint8_t> bytes(
        (std::istreambuf_iterator<char>(stream)),
        std::istreambuf_iterator<char>());
    using mazewall::profiler::CaptureYieldRecorder;
    assert(bytes.size() >= CaptureYieldRecorder::header_size + CaptureYieldRecorder::record_size);
    assert(bytes[0] == 'M' && bytes[1] == 'Z' && bytes[2] == 'C' && bytes[3] == 'Y');
    assert(CaptureYieldRecorder::read_u16_le(bytes, 4) == 1);
    assert(CaptureYieldRecorder::read_u16_le(bytes, 6) == CaptureYieldRecorder::record_size);
    const auto record_count = CaptureYieldRecorder::read_u64_le(bytes, 16);
    assert(record_count > 0);
    assert(bytes.size() == CaptureYieldRecorder::header_size + record_count * CaptureYieldRecorder::record_size);
    return 0;
}
