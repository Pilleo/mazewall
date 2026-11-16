#include "live_startup_binding.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace mazewall::profiler::integrity {
namespace {
constexpr std::array<std::string_view, 30> roles{
    "agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm",
    "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext",
    "libsyslookup", "libzip", "libsimdsort", "release", "modules", "helper", "enforcer", "kotlin_stdlib",
    "serialization_core", "serialization_json", "jetbrains_annotations", "managed_fixture"};
constexpr std::array<std::string_view, 7> classpath{
    "helper", "enforcer", "kotlin_stdlib", "serialization_core", "serialization_json", "jetbrains_annotations", "managed_fixture"};
constexpr std::array<std::string_view, 5> runtime{
    "runtime_version", "vm_name", "vendor", "os_name", "os_arch"};

bool utf8(std::string_view s) {
    for (std::size_t i = 0; i < s.size();) {
        const auto c = static_cast<unsigned char>(s[i++]);
        if (c == 0) return false;
        if (c < 0x80) continue;
        unsigned count = 0;
        std::uint32_t cp = 0;
        if (c >= 0xc2 && c <= 0xdf) { count = 1; cp = c & 0x1f; }
        else if (c >= 0xe0 && c <= 0xef) { count = 2; cp = c & 0x0f; }
        else if (c >= 0xf0 && c <= 0xf4) { count = 3; cp = c & 7; }
        else return false;
        if (count > s.size() - i) return false;
        for (unsigned j = 0; j < count; ++j) {
            const auto d = static_cast<unsigned char>(s[i++]);
            if ((d & 0xc0) != 0x80) return false;
            cp = (cp << 6) | (d & 0x3f);
        }
        if ((count == 2 && cp < 0x800) || (count == 3 && cp < 0x10000)
                || (cp >= 0xd800 && cp <= 0xdfff) || cp > 0x10ffff) return false;
    }
    return true;
}
int digit(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '-') return 62;
    if (c == '_') return 63;
    return -1;
}
bool decode(std::string_view in, std::string& out) {
    if (in.empty() || in.size() > 5462 || in.size() % 4 == 1) return false;
    std::uint32_t accumulator = 0;
    unsigned bits = 0;
    std::string decoded;
    decoded.reserve(in.size() * 3 / 4);
    for (char c : in) {
        const auto value = digit(c);
        if (value < 0) return false;
        accumulator = (accumulator << 6) | static_cast<unsigned>(value);
        bits += 6;
        if (bits >= 8) { bits -= 8; decoded.push_back(static_cast<char>((accumulator >> bits) & 255)); }
    }
    if ((bits && (accumulator & ((1u << bits) - 1u))) || decoded.size() > 4096 || !utf8(decoded)) return false;
    out = std::move(decoded);
    return true;
}
bool hash(std::string_view s) {
    return s.size() == 64 && std::all_of(s.begin(), s.end(), [](char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    });
}
bool path(std::string_view s) {
    if (s.size() < 2 || s[0] != '/' || s.back() == '/' || s.find("//") != s.npos) return false;
    std::size_t start = 1;
    while (start < s.size()) {
        auto end = s.find('/', start);
        if (end == s.npos) end = s.size();
        const auto component = s.substr(start, end - start);
        if (component == "." || component == "..") return false;
        start = end + 1;
    }
    return true;
}
std::vector<std::string> split(std::string_view line) {
    std::vector<std::string> out;
    while (true) {
        const auto pos = line.find('\t');
        out.emplace_back(line.substr(0, pos));
        if (pos == line.npos) return out;
        line.remove_prefix(pos + 1);
    }
}
}
bool parse_manifest(std::string_view bytes, Manifest& result) {
    if (bytes.empty() || bytes.size() > 262144 || bytes.back() != '\n'
            || bytes.find('\r') != bytes.npos || bytes.find('\0') != bytes.npos) return false;
    std::vector<std::vector<std::string>> rows;
    while (!bytes.empty()) {
        const auto end = bytes.find('\n');
        if (end == bytes.npos || end == 0 || end > 8192 || rows.size() >= 1024) return false;
        for (char c : bytes.substr(0, end)) if (static_cast<unsigned char>(c) > 0x7f) return false;
        rows.push_back(split(bytes.substr(0, end)));
        bytes.remove_prefix(end + 1);
    }
    std::size_t pos = 0;
    Manifest parsed;
    auto row = [&](std::string_view tag, std::size_t size) -> std::vector<std::string>* {
        if (pos >= rows.size() || rows[pos].size() != size || rows[pos][0] != tag) return nullptr;
        return &rows[pos++];
    };
    auto exact = [&](std::string_view tag, std::string_view value) {
        auto* r = row(tag, 2); return r && (*r)[1] == value;
    };
    if (!exact("MAZEWALL-MANIFEST", "1") || !exact("PROFILE", "CLOSED_V1") || !exact("VARIANT", "ONLY")) return false;
    auto* source = row("SOURCE", 2);
    std::string value;
    if (!source || !decode((*source)[1], value)) return false;
    parsed.source = value;
    std::size_t runtime_index = 0;
    for (auto key : runtime) {
        auto* r = row("RUNTIME", 3);
        if (!r || (*r)[1] != key || !decode((*r)[2], value)) return false;
        parsed.runtime[runtime_index++] = value;
    }
    for (const auto tag : {"PROCESSARGV", "JVMARG"}) {
        std::size_t count = 0;
        while (pos < rows.size() && rows[pos][0] == tag) {
            auto* r = row(tag, 3);
            if (!r || count >= 128 || (*r)[1] != std::to_string(count++) || !decode((*r)[2], value)) return false;
            (std::string_view(tag) == "PROCESSARGV" ? parsed.process_args : parsed.jvm_args).push_back(value);
        }
        if (count == 0) return false;
    }
    std::vector<std::string> paths;
    for (std::size_t i = 0; i < roles.size(); ++i) {
        auto* r = row("ARTIFACT", 7);
        std::string name, provenance;
        if (!r || (*r)[1] != std::to_string(i) || (*r)[2] != roles[i] || !decode((*r)[3], name)
                || !path(name) || std::find(paths.begin(), paths.end(), name) != paths.end()
                || !hash((*r)[4]) || !decode((*r)[6], provenance)) return false;
        const bool non_elf = roles[i] == "release" || roles[i] == "modules" || i >= 23;
        if (non_elf) { if ((*r)[5] != "sha256:" + (*r)[4]) return false; }
        else if ((*r)[5].empty() || (*r)[5].size() > 128 || (*r)[5].size() % 2 != 0
                || !std::all_of((*r)[5].begin(), (*r)[5].end(), [](char c) {
                    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
                })) return false;
        paths.push_back(name);
        parsed.artifacts.push_back(Artifact{static_cast<ArtifactRole>(i), std::move(name), (*r)[4], (*r)[5], std::move(provenance)});
    }
    for (std::size_t i = 0; i < classpath.size(); ++i) {
        auto* r = row("CLASSPATH", 3);
        if (!r || (*r)[1] != std::to_string(i) || (*r)[2] != classpath[i]) return false;
    }
    constexpr std::array<std::pair<std::string_view, std::string_view>, 12> witnesses{{
        {"io.mazewall.ffi.internal.LiveStackBootstrap", "helper"},
        {"io.mazewall.ffi.internal.ProductionDeploymentVerifier", "helper"},
        {"io.mazewall.ffi.internal.RealNativeStackContexts", "helper"},
        {"io.mazewall.ffi.internal.OwnedStackScopingBootstrap", "enforcer"},
        {"io.mazewall.ffi.internal.OwnedStackScopingBootstrapKt", "enforcer"},
        {"kotlin.jvm.internal.Intrinsics", "kotlin_stdlib"},
        {"kotlinx.serialization.KSerializer", "serialization_core"},
        {"kotlinx.serialization.json.Json", "serialization_json"},
        {"org.jetbrains.annotations.NotNull", "jetbrains_annotations"},
        {"io.mazewall.ffi.internal.OwnedStackScopingPackagedFixture", "managed_fixture"},
        {"io.mazewall.ffi.internal.OwnedStackScopingPackagedBridge", "managed_fixture"},
        {"io.mazewall.ffi.internal.OwnedStackScopingPackagedWorkload", "managed_fixture"}}};
    for (std::size_t i = 0; i < witnesses.size(); ++i) {
        auto* r = row("WITNESS", 4);
        if (!r || (*r)[1] != std::to_string(i) || !decode((*r)[2], value)
                || value != witnesses[i].first || (*r)[3] != witnesses[i].second) return false;
        const auto role = std::find(roles.begin(), roles.end(), witnesses[i].second);
        if (role == roles.end()) return false;
        parsed.witnesses.push_back(Witness{value, static_cast<ArtifactRole>(role - roles.begin())});
    }
    for (std::size_t i = 0; i < 2; ++i) {
        auto* r = row("ROUTE", 3);
        if (!r || (*r)[1] != std::to_string(i) || (*r)[2] != (i == 0 ? "2" : "257")) return false;
    }
    for (std::size_t i = 0; i < 2; ++i) {
        auto* r = row("CONDITIONAL", 4);
        if (!r || (*r)[1] != std::to_string(i) || (*r)[2] != (i == 0 ? "libzip" : "libsimdsort")
                || ((*r)[3] != "present" && (*r)[3] != "absent")) return false;
        parsed.conditional_images[i] = (*r)[3] == "present";
    }
    constexpr std::array<std::string_view, 2> binding_offsets{"0xe950", "0xe9e0"};
    for (std::size_t i = 0; i < 2; ++i) {
        auto* r = row("BINDING", 5);
        if (!r || (*r)[1] != std::to_string(i) || (*r)[2] != std::to_string(i+1)
                || (*r)[3] != "libnio" || (*r)[4] != binding_offsets[i]) return false;
    }
    constexpr std::array<std::array<std::string_view, 4>, 10> dependencies{{
        {"libnio","0x15ee0","libc","0x127990"}, {"libnio","0x16100","libc","0x127a90"},
        {"libnio","0x15c18","libc","0x2a980"}, {"libnio","0x15f40","libjava","0x1a0a0"},
        {"libjvm","0x178cad0","libjvm","0xb03f90"}, {"libjvm","0x178cb08","libjvm","0xb01220"},
        {"libjvm","0x178cb58","libjvm","0xaf7300"}, {"libjvm","0x178cb70","libjvm","0xaf6980"},
        {"libjvm","0x178cb88","libjvm","0xb0f040"}, {"libjvm","0x178cba8","libjvm","0xb05e90"}}};
    for (std::size_t i = 0; i < dependencies.size(); ++i) {
        auto* r = row("DEPENDENCY", 6);
        if (!r || (*r)[1] != std::to_string(i)) return false;
        for (std::size_t j = 0; j < 4; ++j) if ((*r)[j+2] != dependencies[i][j]) return false;
    }
    auto* end = row("END", 1);
    if (!end || pos != rows.size()) return false;
    result = std::move(parsed);
    return true;
}
}
