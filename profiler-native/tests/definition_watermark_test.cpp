#include "../src/agent.cpp"

#include <cassert>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <latch>
#include <thread>

namespace {

std::uint64_t read_le(const std::vector<std::uint8_t>& bytes, std::size_t offset, std::size_t width) {
    assert(offset + width <= bytes.size());
    std::uint64_t value = 0;
    for (std::size_t index = 0; index < width; ++index) {
        value |= std::uint64_t{bytes[offset + index]} << (8 * index);
    }
    return value;
}

void check_snapshot(std::uint64_t watermark, std::initializer_list<std::uint64_t> expected) {
    std::ifstream file(definitions_path, std::ios::binary);
    assert(file.good());
    const std::vector<std::uint8_t> bytes(std::istreambuf_iterator<char>{file}, {});
    assert(read_le(bytes, 24, 8) == watermark);
    assert(read_le(bytes, 32, 8) == bytes.size() - 40);
    std::vector<std::uint64_t> invocations;
    for (std::size_t offset = 46; offset < bytes.size();) {
        const auto length = read_le(bytes, offset + 1, 4);
        const auto payload = offset + 5;
        assert(length <= bytes.size() - payload);
        if (bytes[offset] == 2) invocations.push_back(read_le(bytes, payload, 8));
        offset = payload + length;
    }
    assert(invocations == std::vector<std::uint64_t>(expected));
}

}  // namespace

int main(int argc, char** argv) {
    assert(argc == 2);
    definitions_path = argv[1];
    session_bound_definitions.store(true);
    std::latch first_allocated{1};
    std::latch first_can_complete{1};
    std::jthread first([&] {
        const auto id = next_invocation_id.fetch_add(1);
        first_allocated.count_down();
        first_can_complete.wait();
        assert(record_invocation(InvocationDefinition{id, 0, 0, 1, 0}));
    });
    first_allocated.wait();
    const auto later_id = next_invocation_id.fetch_add(1);
    assert(later_id == 2);
    assert(record_invocation(InvocationDefinition{later_id, 0, 0, 1, 0}));
    assert(write_definitions());
    check_snapshot(later_id, {later_id});
    std::filesystem::copy_file(definitions_path, definitions_path + ".first", std::filesystem::copy_options::overwrite_existing);

    first_can_complete.count_down();
    first.join();
    assert(write_definitions(true));
    check_snapshot(later_id, {later_id, 1});
}
