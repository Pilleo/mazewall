#include "live_stack_checked_projection.hpp"

#include <array>
#include <climits>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <fcntl.h>
#include <unistd.h>
#include <string_view>

bool t4_legacy_image_index(const ParsedProjection& projection, size_t legacy_slot, size_t& index) noexcept {
    static constexpr std::array<std::string_view, 10> roles{
        "agent", "", "java", "", "", "libjvm", "libjava", "libnio", "libc", "loader",
    };
    if (legacy_slot >= roles.size() || roles[legacy_slot].empty() ||
        projection.roles.size() != projection.paths.size() || projection.roles.size() != projection.builds.size()) return false;
    bool found = false;
    size_t selected = 0;
    for (size_t n = 0; n < projection.roles.size(); ++n) {
        if (projection.roles[n] != roles[legacy_slot]) continue;
        if (found) return false;
        found = true;
        selected = n;
    }
    if (!found) return false;
    index = selected;
    return true;
}

bool t4_checked_shape(const ParsedProjection& projection) {
    static constexpr std::array<std::string_view, 18> required{
        "agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm",
        "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext",
    };
    if ((projection.profile != ProjectionProfile::Closed && projection.profile != ProjectionProfile::Retransform) ||
        (projection.zip != ProjectionBranch::Absent && projection.zip != ProjectionBranch::Present) ||
        (projection.simd != ProjectionBranch::Absent && projection.simd != ProjectionBranch::Present)) return false;
    if (projection.version != ProjectionVersion::T4V1 && projection.version != ProjectionVersion::T4V2) return false;
    if (projection.version == ProjectionVersion::T4V2 && projection.profile != ProjectionProfile::Closed) return false;
    const size_t size = required.size() + (projection.version == ProjectionVersion::T4V2 ? 1 : 0) + (projection.zip == ProjectionBranch::Present ? 1 : 0) +
        (projection.simd == ProjectionBranch::Present ? 1 : 0) + (projection.profile == ProjectionProfile::Retransform ? 1 : 0);
    if (projection.roles.size() != size || projection.paths.size() != size || projection.builds.size() != size ||
        projection.mask < 1 || projection.mask > 3) return false;
    for (size_t n = 0; n < required.size(); ++n) if (projection.roles[n] != required[n]) return false;
    size_t next = required.size();
    if (projection.version == ProjectionVersion::T4V2 && projection.roles[next++] != "libsyslookup") return false;
    if (projection.zip == ProjectionBranch::Present && projection.roles[next++] != "libzip") return false;
    if (projection.simd == ProjectionBranch::Present && projection.roles[next++] != "libsimdsort") return false;
    if (projection.profile == ProjectionProfile::Retransform && projection.roles[next++] != "libinstrument") return false;
    for (size_t n = 0; n < size; ++n) {
        if (!projection_path(projection.paths[n]) || !projection_build(projection.builds[n])) return false;
        for (size_t prior = 0; prior < n; ++prior) if (projection.paths[prior] == projection.paths[n]) return false;
    }
    return true;
}

namespace mazewall::live::jni {
bool read_mappings(std::vector<Mapping>& result) {
    std::ifstream input("/proc/self/maps");
    std::string line;
    while (std::getline(input, line)) {
        unsigned long low = 0, high = 0, offset = 0, inode = 0;
        unsigned major = 0, minor = 0;
        char permissions[5]{};
        if (std::sscanf(line.c_str(), "%lx-%lx %4s %lx %x:%x %lu", &low, &high, permissions, &offset, &major, &minor, &inode) != 7) return false;
        result.push_back({low, high, inode, major, minor, permissions[0] == 'r', permissions[1] == 'w', permissions[2] == 'x', line});
    }
    return input.eof() && !result.empty();
}
bool range(const Deployment& d, const Image& owner, uintptr_t address, size_t bytes, bool executable, std::string* problem) {
    if (!bytes || address > UINTPTR_MAX - bytes) return false;
    for (const auto& m : d.mappings) {
        if (address < m.low || address + bytes > m.high || !m.read || (executable && !m.execute) || m.inode != owner.file.st_ino) continue;
        char path[128];
        std::snprintf(path, sizeof(path), "/proc/self/map_files/%lx-%lx", static_cast<unsigned long>(m.low), static_cast<unsigned long>(m.high));
        const int fd = open(path, O_RDONLY | O_CLOEXEC);
        const int open_error = errno;
        if (fd < 0) {
            if (problem) *problem = "mapped descriptor open failed errno=" + std::to_string(open_error) + " " + path;
            return false;
        }
        struct stat mapped{};
        const int result = fstat(fd, &mapped);
        const int stat_error = result != 0 ? errno : 0;
        const int close_result = close(fd);
        const int close_error = close_result != 0 ? errno : 0;
        if (result != 0) {
            if (problem) *problem = "mapped descriptor fstat failed errno=" + std::to_string(stat_error);
            return false;
        }
        if (d.strict_close && close_result != 0) {
            if (problem) *problem = "mapped descriptor close failed errno=" + std::to_string(close_error);
            return false;
        }
        // maps and stat device numbers differ on Btrfs; compare actual opened objects.
        if (S_ISREG(mapped.st_mode) && mapped.st_ino == owner.file.st_ino && mapped.st_dev == owner.file.st_dev) return true;
        if (problem) *problem = "mapped descriptor does not match measured owner";
        return false;
    }
    return false;
}
int image_callback(dl_phdr_info* info, size_t, void* data) noexcept {
    try {
    auto& d = *static_cast<Deployment*>(data);
    const char* raw = info->dlpi_name[0] ? info->dlpi_name : "/proc/self/exe";
    char resolved[PATH_MAX];
    if (!realpath(raw, resolved)) return 0;
    for (size_t n = 0; n < d.paths.size(); ++n) {
        if (d.paths[n] != resolved) continue;
        const int fd = open(resolved, O_RDONLY | O_CLOEXEC);
        if (fd < 0) { if (d.strict_close) { d.inspection_error = errno; return 1; } return 0; }
        struct stat file{};
        const int status = fstat(fd, &file);
        const int stat_error = status != 0 ? errno : 0;
        const int close_result = close(fd);
        const int close_error = close_result != 0 ? errno : 0;
        if (status != 0) { if (d.strict_close) { d.inspection_error = stat_error; return 1; } return 0; }
        if (d.strict_close && close_result != 0) { d.inspection_error = close_error; return 1; }
        if (!S_ISREG(file.st_mode)) return d.strict_close ? 1 : 0;
        d.images[n] = {resolved, info->dlpi_addr, info->dlpi_phdr, info->dlpi_phnum, file};
    }
    return 0;
    } catch (...) { return 1; }
}
bool build_identity(const Deployment& d, const Image& image, const std::string& expected, std::string* problem) {
    const auto deny = [&](const std::string& message) { if (problem) *problem = message + ": " + image.path; return false; };
    if (image.path.empty()) return deny("loader image absent");
    std::string range_problem;
    if (!range(d, image, reinterpret_cast<uintptr_t>(image.headers), image.count * sizeof(ElfW(Phdr)), false, &range_problem)) return deny("loader program headers not readable in mapped owner: " + range_problem);
    for (size_t n = 0; n < image.count; ++n) {
        const auto& ph = image.headers[n];
        if (ph.p_type != PT_NOTE || ph.p_memsz > 65536 || image.base > UINTPTR_MAX - ph.p_vaddr) continue;
        const auto start = image.base + ph.p_vaddr;
        if (!range(d, image, start, ph.p_memsz, false)) return deny("loader note range not readable in mapped owner");
        size_t position = 0;
        while (position + sizeof(ElfW(Nhdr)) <= ph.p_memsz) {
            ElfW(Nhdr) note{}; std::memcpy(&note, reinterpret_cast<void*>(start + position), sizeof(note));
            position += sizeof(note);
            const size_t names = (static_cast<size_t>(note.n_namesz) + 3u) & ~size_t(3);
            const size_t desc = (static_cast<size_t>(note.n_descsz) + 3u) & ~size_t(3);
            if (names > ph.p_memsz - position || desc > ph.p_memsz - position - names) return deny("malformed loader note bounds");
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4 && std::memcmp(reinterpret_cast<void*>(start + position), "GNU", 4) == 0) {
                const auto* bytes = reinterpret_cast<const unsigned char*>(start + position + names);
                std::string actual;
                for (size_t b = 0; b < note.n_descsz; ++b) { actual += "0123456789abcdef"[bytes[b] >> 4]; actual += "0123456789abcdef"[bytes[b] & 15]; }
                return actual == expected || deny("loader build ID mismatch actual=" + actual + " expected=" + expected);
            }
            position += names + desc;
        }
    }
    return deny("loader GNU build ID absent");
}
namespace {
struct T4ImageProbe {
    Deployment* deployment;
    bool seen = false;
    int stage = 0;
    int error = 0;
    int realpath_error = 0;
    unsigned realpath_failures = 0;
};
int t4_image_callback(dl_phdr_info* info, size_t, void* data) noexcept {
    auto& probe = *static_cast<T4ImageProbe*>(data);
    const char* raw = info->dlpi_name[0] ? info->dlpi_name : "/proc/self/exe";
    char resolved[PATH_MAX];
    if (!realpath(raw, resolved)) {
        probe.realpath_error = errno;
        ++probe.realpath_failures;
        return 0;
    }
    if (probe.deployment->paths[0] != resolved) return 0;
    if (probe.seen) { probe.stage = 1; return 1; }
    probe.seen = true;
    const int fd = open(resolved, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { probe.stage = 2; probe.error = errno; return 0; }
    struct stat file{};
    const int status = fstat(fd, &file);
    const int stat_error = status != 0 ? errno : 0;
    const int close_result = close(fd);
    const int close_error = close_result != 0 ? errno : 0;
    if (status != 0) { probe.stage = 3; probe.error = stat_error; return 0; }
    if (probe.deployment->strict_close && close_result != 0) { probe.stage = 6; probe.error = close_error; return 0; }
    if (!S_ISREG(file.st_mode)) { probe.stage = 4; return 0; }
    try {
        probe.deployment->images[0] = {resolved, info->dlpi_addr, info->dlpi_phdr, info->dlpi_phnum, file};
    } catch (...) { probe.stage = 5; return 1; }
    return 0;
}
struct ConditionalImages {
    bool zip = false;
    bool simd = false;
    bool instrument = false;
};
int inspect_conditional(dl_phdr_info* info, size_t, void* data) noexcept {
    auto& found = *static_cast<ConditionalImages*>(data);
    const char* name = std::strrchr(info->dlpi_name, '/');
    name = name ? name + 1 : info->dlpi_name;
    if (std::strcmp(name, "libzip.so") == 0) found.zip = true;
    if (std::strcmp(name, "libsimdsort.so") == 0) found.simd = true;
    if (std::strcmp(name, "libinstrument.so") == 0) found.instrument = true;
    return 0;
}
bool conditional_images(const Deployment& d, std::string* problem) {
    ConditionalImages found;
    const int result = dl_iterate_phdr(inspect_conditional, &found);
    const bool expected_zip = d.t4_zip == ProjectionBranch::Present;
    const bool expected_simd = d.t4_simd == ProjectionBranch::Present;
    const bool expected_instrument = d.t4_profile == ProjectionProfile::Retransform;
    if (result == 0 && found.zip == expected_zip && found.simd == expected_simd && found.instrument == expected_instrument) return true;
    if (problem) *problem = "conditional loader result=" + std::to_string(result) +
        " zip=" + std::to_string(found.zip) + "/" + std::to_string(expected_zip) +
        " simd=" + std::to_string(found.simd) + "/" + std::to_string(expected_simd) +
        " instrument=" + std::to_string(found.instrument) + "/" + std::to_string(expected_instrument);
    return false;
}
} // namespace

bool verify_t4_images(const Deployment& d, std::string* problem) {
    if (d.t4_paths.empty()) return true; // Existing 21-string legacy input.
    if (d.t4_paths.size() != d.t4_images.size() || d.t4_paths.size() != d.t4_builds.size()) {
        if (problem) *problem = "image vector size mismatch";
        return false;
    }
    if (!conditional_images(d, problem)) return false;
    // Every actual file-backed executable mapping must belong to an admitted image.
    // Anonymous JVM-generated code and kernel mappings have no file identity here.
    for (const auto& mapping : d.mappings) {
        if (!mapping.execute || mapping.inode == 0) continue;
        bool admitted = false;
        std::string candidate_problem;
        size_t candidate_index = 0;
        bool candidate_found = false;
        for (size_t n = 0; n < d.t4_images.size(); ++n) {
            const auto& image = d.t4_images[n];
            std::string detail;
            const bool candidate = mapping.inode == image.file.st_ino;
            if (range(d, image, mapping.low, 1, true, candidate ? &detail : nullptr)) { admitted = true; break; }
            if (candidate && !candidate_found) {
                candidate_found = true;
                candidate_index = n;
                candidate_problem = detail.empty() ? "no matching readable executable range" : detail;
            }
        }
        if (!admitted) {
            if (problem) {
                *problem = "unrecognized executable process mapping row=" + mapping.original_row;
                if (candidate_found) *problem += " candidate index=" + std::to_string(candidate_index) +
                    " path=" + d.t4_paths[candidate_index] + " reason=" + candidate_problem;
            }
            return false;
        }
    }
    for (size_t n = 0; n < d.t4_paths.size(); ++n) {
        struct stat current{};
        const int status = stat(d.t4_paths[n].c_str(), &current);
        const int stat_error = errno;
        const auto label = "image index=" + std::to_string(n) + " expected=" + d.t4_paths[n];
        if (status != 0) { if (problem) *problem = label + " stat errno=" + std::to_string(stat_error); return false; }
        if (!S_ISREG(current.st_mode)) { if (problem) *problem = label + " not regular"; return false; }
        if (current.st_dev != d.t4_images[n].file.st_dev || current.st_ino != d.t4_images[n].file.st_ino) {
            if (problem) *problem = label + " stat identity mismatch";
            return false;
        }
        if (!build_identity(d, d.t4_images[n], d.t4_builds[n], problem)) {
            if (problem) *problem = label + " " + *problem;
            return false;
        }
        for (size_t prior = 0; prior < n; ++prior) {
            if (current.st_dev == d.t4_images[prior].file.st_dev && current.st_ino == d.t4_images[prior].file.st_ino) {
                if (problem) *problem = label + " aliases index=" + std::to_string(prior);
                return false;
            }
        }
    }
    return true;
}

bool inspect_t4_images(const ParsedProjection& projection, Deployment& out, std::string* problem) {
    if (!t4_checked_shape(projection)) { if (problem) *problem = "projection shape invalid"; return false; }
    if (!read_mappings(out.mappings)) { if (problem) *problem = "initial process mappings unavailable or malformed"; return false; }
    out.t4_paths = projection.paths;
    out.t4_builds = projection.builds;
    out.t4_zip = projection.zip;
    out.t4_simd = projection.simd;
    out.t4_profile = projection.profile;
    out.t4_images.reserve(projection.roles.size());
    for (size_t n = 0; n < projection.roles.size(); ++n) {
        Deployment probe;
        probe.strict_close = out.strict_close;
        probe.paths[0] = projection.paths[n];
        T4ImageProbe actual{&probe};
        const int result = dl_iterate_phdr(t4_image_callback, &actual);
        if (result != 0 || !actual.seen || probe.images[0].path.empty()) {
            if (problem) *problem = "loader role=" + projection.roles[n] + " index=" + std::to_string(n) +
                " expected=" + projection.paths[n] + " result=" + std::to_string(result) +
                " seen=" + std::to_string(actual.seen) + " stage=" + std::to_string(actual.stage) +
                " realpath_failures=" + std::to_string(actual.realpath_failures) +
                (actual.realpath_failures ? " last_realpath_errno=" + std::to_string(actual.realpath_error) : "") +
                ((actual.stage == 2 || actual.stage == 3) ? " errno=" + std::to_string(actual.error) : "");
            return false;
        }
        out.t4_images.push_back(std::move(probe.images[0]));
    }
    if (!verify_t4_images(out, problem)) {
        if (problem) {
            std::string roles = " roles=";
            for (size_t n = 0; n < projection.roles.size(); ++n) {
                if (n) roles += ",";
                roles += std::to_string(n) + ":" + projection.roles[n];
            }
            *problem += roles;
        }
        return false;
    }
    for (size_t legacy : {0u, 2u, 5u, 6u, 7u, 8u, 9u}) {
        size_t index = 0;
        if (!t4_legacy_image_index(projection, legacy, index)) { if (problem) *problem = "legacy image index=" + std::to_string(legacy); return false; }
        out.paths[legacy] = out.t4_paths[index];
        out.builds[legacy] = out.t4_builds[index];
        out.images[legacy] = out.t4_images[index];
    }
    return true;
}
} // namespace mazewall::live::jni
