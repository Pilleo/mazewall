#include <cassert>
#include <cstdint>
#include <fstream>
#include <iterator>
#include <vector>

namespace {

std::uint16_t read_u16_le(const std::vector<std::uint8_t>& bytes, std::size_t offset) {
    return static_cast<std::uint16_t>(bytes[offset]) |
        (static_cast<std::uint16_t>(bytes[offset + 1]) << 8);
}

std::uint64_t read_u64_le(const std::vector<std::uint8_t>& bytes, std::size_t offset) {
    std::uint64_t value = 0;
    for (std::size_t index = 0; index < sizeof(value); ++index) {
        value |= static_cast<std::uint64_t>(bytes[offset + index]) << (index * 8);
    }
    return value;
}

} // namespace

int main(int argc, char** argv) {
    assert(argc == 3);
    std::ifstream stream(argv[1], std::ios::binary);
    assert(stream.good());
    const std::vector<std::uint8_t> bytes(
        (std::istreambuf_iterator<char>(stream)),
        std::istreambuf_iterator<char>());

    // MZSE v2 is 40 bytes before the unchanged MZSD v2 payload.
    assert(bytes.size() > 40);
    assert(bytes[0] == 'M' && bytes[1] == 'Z' && bytes[2] == 'S' && bytes[3] == 'E');
    assert(read_u16_le(bytes, 4) == 2);
    assert((read_u16_le(bytes, 6) & 1U) == 1U);
    assert(read_u64_le(bytes, 8) == std::stoull(argv[2], nullptr, 16));
    const auto payload_length = read_u64_le(bytes, 32);
    assert(payload_length == bytes.size() - 40);
    assert(bytes[40] == 'M' && bytes[41] == 'Z' && bytes[42] == 'S' && bytes[43] == 'D');
    assert(bytes[44] == 2);
    return 0;
}
