#pragma once

#include "live_stack_projection.hpp"
#include <cstddef>
#include <array>
#include <cstdint>
#include <link.h>
#include <sys/stat.h>

// Map only actual ELF roles shared with the legacy deployment layout.
// Helper, release and modules are Java artifacts, not mapped ELF images.
bool t4_legacy_image_index(const ParsedProjection& projection, size_t legacy_slot, size_t& index) noexcept;

// Recheck the closed t4 role schema before actual process inspection.
bool t4_checked_shape(const ParsedProjection& projection);

namespace mazewall::live::jni {
struct Mapping {
    uintptr_t low, high;
    uint64_t inode;
    unsigned device_major, device_minor;
    bool read, write, execute;
    std::string original_row;
};
struct Image {
    std::string path;
    uintptr_t base;
    const ElfW(Phdr)* headers;
    size_t count;
    struct stat file;
};
struct Deployment {
    bool strict_close = false;
    int inspection_error = 0;
    std::array<std::string, 10> paths, builds;
    std::array<Image, 10> images{};
    std::vector<Mapping> mappings;
    std::vector<std::string> t4_paths, t4_builds;
    std::vector<Image> t4_images;
    ProjectionBranch t4_zip = ProjectionBranch::Absent, t4_simd = ProjectionBranch::Absent;
    ProjectionProfile t4_profile = ProjectionProfile::Closed;
};
bool read_mappings(std::vector<Mapping>& result);
bool range(const Deployment& d, const Image& owner, uintptr_t address, size_t bytes, bool executable, std::string* problem = nullptr);
int image_callback(dl_phdr_info* info, size_t size, void* data) noexcept;
bool build_identity(const Deployment& d, const Image& image, const std::string& expected, std::string* problem);
bool inspect_t4_images(const ParsedProjection& projection, Deployment& out, std::string* problem);
bool verify_t4_images(const Deployment& deployment, std::string* problem);
} // namespace mazewall::live::jni
