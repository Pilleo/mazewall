#pragma once
#include <string>
#include <vector>
#include <utility>

enum class ProjectionVersion { T4V1, T4V2 };
enum class ProjectionProfile { Closed, Retransform };
enum class ProjectionBranch { Absent, Present };
struct ParsedProjection {
    ProjectionVersion version = ProjectionVersion::T4V1;
    ProjectionProfile profile = ProjectionProfile::Closed;
    ProjectionBranch zip = ProjectionBranch::Absent;
    ProjectionBranch simd = ProjectionBranch::Absent;
    std::vector<std::string> roles;
    std::vector<std::string> paths;
    std::vector<std::string> builds;
    int mask = 0;
};
inline bool projection_path(const std::string& value) {
    if (value.empty() || value.size() > 4096 || value.front() != '/' || value.back() == '/' || (value.find('\0') != std::string::npos || value.find("\xc0\x80") != std::string::npos) || value.find("//") != std::string::npos) return false;
    size_t start = 1;
    while (start < value.size()) {
        const auto end = value.find('/', start);
        const auto part = value.substr(start, end == std::string::npos ? end : end - start);
        if (part == "." || part == "..") return false;
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return true;
}
inline bool projection_build(const std::string& value) {
    if (value.size() < 2 || value.size() > 128 || value.size() % 2 != 0) return false;
    for (char c : value) if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    return true;
}
inline bool parse_t4_v1(const std::vector<std::string>& in, ParsedProjection& out) {
    if (in.size() < 59 || in.size() > 68 || in[0] != "mazewall-t4/1" || (in[1] != "closed" && in[1] != "retransform") ||
        (in[2] != "zip-present" && in[2] != "zip-absent") || (in[3] != "simd-present" && in[3] != "simd-absent") ||
        (in[4] != "1" && in[4] != "2" && in[4] != "3")) return false;
    std::vector<std::string> roles{"agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm", "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext"};
    if (in[2] == "zip-present") roles.emplace_back("libzip");
    if (in[3] == "simd-present") roles.emplace_back("libsimdsort");
    if (in[1] == "retransform") roles.emplace_back("libinstrument");
    if (in.size() != 5 + 3 * roles.size()) return false;
    ParsedProjection result; result.mask = in[4][0] - '0';
    result.profile = in[1] == "closed" ? ProjectionProfile::Closed : ProjectionProfile::Retransform;
    result.zip = in[2] == "zip-present" ? ProjectionBranch::Present : ProjectionBranch::Absent;
    result.simd = in[3] == "simd-present" ? ProjectionBranch::Present : ProjectionBranch::Absent;
    for (size_t n = 0; n < roles.size(); ++n) {
        if (in[5 + 3*n] != roles[n] || !projection_path(in[6 + 3*n]) || !projection_build(in[7 + 3*n])) return false;
        result.roles.push_back(roles[n]); result.paths.push_back(in[6 + 3*n]); result.builds.push_back(in[7 + 3*n]);
    }
    out = std::move(result); return true;
}

inline bool parse_t4_v2(const std::vector<std::string>& in, ParsedProjection& out) {
    if (in.size() < 62 || in.size() > 68 || in[0] != "mazewall-t4/2" || in[1] != "closed" ||
        (in[2] != "zip-present" && in[2] != "zip-absent") || (in[3] != "simd-present" && in[3] != "simd-absent") ||
        (in[4] != "1" && in[4] != "2" && in[4] != "3")) return false;
    std::vector<std::string> roles{"agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm", "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext"};
    roles.emplace_back("libsyslookup");
    if (in[2] == "zip-present") roles.emplace_back("libzip");
    if (in[3] == "simd-present") roles.emplace_back("libsimdsort");
    if (in.size() != 5 + 3 * roles.size()) return false;
    ParsedProjection result; result.version = ProjectionVersion::T4V2; result.mask = in[4][0] - '0';
    result.profile = in[1] == "closed" ? ProjectionProfile::Closed : ProjectionProfile::Retransform;
    result.zip = in[2] == "zip-present" ? ProjectionBranch::Present : ProjectionBranch::Absent;
    result.simd = in[3] == "simd-present" ? ProjectionBranch::Present : ProjectionBranch::Absent;
    for (size_t n = 0; n < roles.size(); ++n) {
        if (in[5 + 3*n] != roles[n] || !projection_path(in[6 + 3*n]) || !projection_build(in[7 + 3*n])) return false;
        for (const auto& prior : result.paths) if (prior == in[6 + 3*n]) return false;
        result.roles.push_back(roles[n]); result.paths.push_back(in[6 + 3*n]); result.builds.push_back(in[7 + 3*n]);
    }
    out = std::move(result); return true;
}
