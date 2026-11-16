#include "live_startup_binding.hpp"

#include <algorithm>
#include <utility>

namespace mazewall::profiler::integrity {
bool parse_binding(std::string_view bytes, Binding& result) {
    constexpr std::string_view prefix =
        "MAZEWALL-BINDING\t1\nMANIFEST\tL2V0Yy9tYXpld2FsbC9saXZlL2Rpc3RyaWJ1dGlvbi52MQ\t";
    constexpr std::string_view suffix = "\nEND\n";
    if (bytes.size() > 16384 || bytes.size() != prefix.size() + 64 + suffix.size()
            || !bytes.starts_with(prefix) || !bytes.ends_with(suffix)) return false;
    const auto hash = bytes.substr(prefix.size(), 64);
    if (!std::all_of(hash.begin(), hash.end(), [](char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
        })) return false;
    Binding valid{std::string(hash)};
    result = std::move(valid);
    return true;
}
}
