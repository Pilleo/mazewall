#pragma once

#include <charconv>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <fcntl.h>
#include <optional>
#include <sstream>
#include <string>
#include <string_view>
#include <system_error>
#include <unistd.h>

namespace mazewall::profiler::marker_proof {

// /proc/self/stat begins with pid and a parenthesized comm which may contain spaces.
// Linux starttime is field 22, hence token 19 after the final closing parenthesis.
inline std::optional<std::uint64_t> process_starttime(std::string_view stat) {
    const auto end_comm = stat.rfind(')');
    if (end_comm == std::string_view::npos) return std::nullopt;
    std::istringstream fields{std::string(stat.substr(end_comm + 1))};
    std::string field;
    for (int index = 0; index <= 19; ++index) {
        if (!(fields >> field)) return std::nullopt;
    }
    std::uint64_t start = 0;
    const auto parsed = std::from_chars(field.data(), field.data() + field.size(), start);
    if (parsed.ec != std::errc{} || parsed.ptr != field.data() + field.size() || start == 0) return std::nullopt;
    return start;
}

inline std::string format(std::uint32_t session_tag, std::uint64_t starttime, bool marker_v2) {
    std::ostringstream proof;
    proof << "version=1\nsession_tag=" << std::hex << session_tag << std::dec
          << "\nprocess_starttime=" << starttime << "\nmarker_version=" << (marker_v2 ? "v2" : "v1") << '\n';
    return proof.str();
}

// A same-directory exclusive temporary file plus rename provides complete
// pathname visibility without granting any privilege to the target JVM.
inline bool write_atomic(const std::string& destination, const std::string& contents) {
    if (destination.empty() || destination.front() != '/' || contents.size() > 512) return false;
    const auto temporary = destination + ".tmp." + std::to_string(::getpid());
    const int fd = ::open(temporary.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return false;
    bool written = true;
    std::size_t offset = 0;
    while (offset < contents.size()) {
        const auto count = ::write(fd, contents.data() + offset, contents.size() - offset);
        if (count > 0) offset += static_cast<std::size_t>(count);
        else if (count < 0 && errno == EINTR) continue;
        else { written = false; break; }
    }
    if (::close(fd) != 0) written = false;
    if (written && ::rename(temporary.c_str(), destination.c_str()) == 0) return true;
    (void)::unlink(temporary.c_str());
    return false;
}

} // namespace mazewall::profiler::marker_proof
