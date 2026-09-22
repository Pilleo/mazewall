#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string_view>
#include <vector>

namespace {

bool contains(const std::vector<char>& image, std::string_view value) {
    return std::search(image.begin(), image.end(), value.begin(), value.end()) != image.end();
}

std::uint32_t u32(const std::vector<char>& image, std::size_t offset) {
    if (offset + sizeof(std::uint32_t) > image.size()) std::exit(EXIT_FAILURE);
    std::uint32_t value{};
    std::memcpy(&value, image.data() + offset, sizeof(value));
    return value;
}

bool has_stapsdt_note(const std::vector<char>& image) {
    for (std::size_t name_offset = 12; name_offset + 8 <= image.size(); ++name_offset) {
        if (std::string_view(image.data() + name_offset, 8) == std::string_view("stapsdt\0", 8) &&
            u32(image, name_offset - 12) == 8 && u32(image, name_offset - 4) == 3) {
            return true;
        }
    }
    return false;
}

} // namespace

int main(int argc, char** argv) {
    if (argc != 3) return EXIT_FAILURE;
    std::ifstream input(argv[1], std::ios::binary);
    const std::vector<char> image((std::istreambuf_iterator<char>(input)), std::istreambuf_iterator<char>());
    if (input.bad()) return EXIT_FAILURE;
    const bool enabled = std::string_view(argv[2]) == "enabled";
    const bool has_note_name = has_stapsdt_note(image);
    const bool has_provider = contains(image, "mazewall");
    const bool has_probe = contains(image, "stack_marker_v1");
    const bool has_arguments = contains(image, "8@%rdi 4@%esi");
    const bool has_probe_v2 = contains(image, "stack_marker_v2");
    const bool has_arguments_v2 = contains(image, "8@%rdi 8@%rsi 4@%edx");
    if (!enabled) return has_note_name ? EXIT_FAILURE : EXIT_SUCCESS;
    return (has_note_name && has_provider && has_probe && has_arguments && has_probe_v2 && has_arguments_v2) ? EXIT_SUCCESS : EXIT_FAILURE;
}
