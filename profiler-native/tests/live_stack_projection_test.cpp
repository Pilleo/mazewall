#include "../src/live_stack_projection.hpp"
#include "../src/live_stack_checked_projection.hpp"
#include <cassert>
#include <string>
#include <vector>
int main() {
    const std::vector<std::vector<std::string>> expected{
        {"agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm", "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext"},
        {"agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm", "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext", "libzip"},
        {"agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm", "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext", "libsimdsort"},
        {"agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm", "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext", "libzip", "libsimdsort"},
        {"agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm", "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext", "libinstrument"},
        {"agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm", "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext", "libzip", "libinstrument"},
        {"agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm", "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext", "libsimdsort", "libinstrument"},
        {"agent", "java", "libjvm", "libjava", "libnio", "libc", "loader", "libstdc++", "libgcc_s", "libm", "libdl", "libpthread", "librt", "libnet", "libjli", "libjimage", "libmanagement", "libmanagement_ext", "libzip", "libsimdsort", "libinstrument"},
    };
    for (int profile = 0; profile < 2; ++profile) for (int branch = 0; branch < 4; ++branch) for (int mask = 1; mask <= 3; ++mask) {
        const auto& roles = expected[profile * 4 + branch];
        std::vector<std::string> in{"mazewall-t4/1", profile ? "retransform" : "closed", branch & 1 ? "zip-present" : "zip-absent", branch & 2 ? "simd-present" : "simd-absent", std::to_string(mask)};
        for (const auto& role : roles) { in.push_back(role); in.push_back("/native/" + role); in.emplace_back("ab"); }
        ParsedProjection out{}; assert(parse_t4_v1(in, out)); assert(out.roles == roles && out.mask == mask);
        assert(t4_checked_shape(out));
        auto aliased = out; aliased.paths[1] = aliased.paths[0];
        assert(!t4_checked_shape(aliased));
        auto wrong_role = out; wrong_role.roles[0] = wrong_role.roles[1];
        assert(!t4_checked_shape(wrong_role));
        const std::vector<std::pair<size_t, std::string>> legacy_images{
            {0, "agent"}, {2, "java"}, {5, "libjvm"}, {6, "libjava"},
            {7, "libnio"}, {8, "libc"}, {9, "loader"},
        };
        for (const auto& [slot, role] : legacy_images) {
            size_t index = 0;
            assert(t4_legacy_image_index(out, slot, index));
            assert(index < out.roles.size() && out.roles[index] == role);
        }
        for (size_t slot : {1u, 3u, 4u, 10u}) {
            size_t index = 0;
            assert(!t4_legacy_image_index(out, slot, index));
        }
        auto duplicate = out;
        duplicate.roles.push_back("agent"); duplicate.paths.push_back("/duplicate"); duplicate.builds.push_back("ab");
        size_t index = 0;
        assert(!t4_legacy_image_index(duplicate, 0, index));
        duplicate = out; duplicate.paths.pop_back();
        assert(!t4_legacy_image_index(duplicate, 0, index));
        assert(out.profile == (profile ? ProjectionProfile::Retransform : ProjectionProfile::Closed));
        assert(out.zip == (branch & 1 ? ProjectionBranch::Present : ProjectionBranch::Absent));
        assert(out.simd == (branch & 2 ? ProjectionBranch::Present : ProjectionBranch::Absent));
        auto bad = in;
        for (const auto& token : {std::string(""), std::string("unknown"), std::string("mazewall-t4/2")}) { bad = in; bad[0] = token; assert(!parse_t4_v1(bad, out)); }
        bad = in; bad[1] = "unknown"; assert(!parse_t4_v1(bad, out));
        bad = in; bad[2] = "unknown"; assert(!parse_t4_v1(bad, out));
        bad = in; bad[3] = "unknown"; assert(!parse_t4_v1(bad, out));
        bad = in; bad[6] = std::string(4097, 'x'); assert(!parse_t4_v1(bad, out));
        bad = in; bad[7] = std::string(130, 'a'); assert(!parse_t4_v1(bad, out));
        bad = in; bad[5] = "wrong"; assert(!parse_t4_v1(bad, out)); assert(out.roles == roles);
        bad = in; bad[6] = "/a/../b"; assert(!parse_t4_v1(bad, out));
        bad = in; bad[7] = "AB"; assert(!parse_t4_v1(bad, out));
        bad = in; bad[4] = "01"; assert(!parse_t4_v1(bad, out));
        bad = in; bad.pop_back(); assert(!parse_t4_v1(bad, out));
        bad = in; bad.push_back("extra"); assert(!parse_t4_v1(bad, out));
        bad = in; bad[2] = branch & 1 ? "zip-absent" : "zip-present"; bad[3] = branch & 2 ? "simd-absent" : "simd-present"; assert(!parse_t4_v1(bad, out));
    }
    for (int branch = 0; branch < 4; ++branch) for (int mask = 1; mask <= 3; ++mask) {
        auto roles = expected[branch];
        roles.insert(roles.begin() + 18, "libsyslookup");
        std::vector<std::string> in{"mazewall-t4/2", "closed", branch & 1 ? "zip-present" : "zip-absent", branch & 2 ? "simd-present" : "simd-absent", std::to_string(mask)};
        for (const auto& role : roles) { in.push_back(role); in.push_back("/native/" + role); in.emplace_back("ab"); }
        ParsedProjection out{}; assert(parse_t4_v2(in, out)); assert(out.roles == roles && t4_checked_shape(out));
        for (size_t slot : {0u, 2u, 5u, 6u, 7u, 8u, 9u}) {
            size_t actual = 0, original = 0;
            auto v1 = out; v1.version = ProjectionVersion::T4V1;
            v1.roles.erase(v1.roles.begin() + 18); v1.paths.erase(v1.paths.begin() + 18); v1.builds.erase(v1.builds.begin() + 18);
            assert(t4_legacy_image_index(out, slot, actual) && t4_legacy_image_index(v1, slot, original));
            assert(out.roles[actual] == v1.roles[original] && out.paths[actual] == v1.paths[original]);
        }
        auto missing = out; missing.roles.erase(missing.roles.begin() + 18);
        assert(!t4_checked_shape(missing));
        auto alias = out; alias.paths[18] = alias.paths[0]; assert(!t4_checked_shape(alias));
        auto bad = in; bad[1] = "retransform"; assert(!parse_t4_v2(bad, out)); assert(out.roles == roles);
        bad = in; bad[5 + 3 * 18] = "libzip"; assert(!parse_t4_v2(bad, out));
        bad = in; bad[6 + 3 * 18] = bad[6]; assert(!parse_t4_v2(bad, out));
        bad = in; bad.pop_back(); assert(!parse_t4_v2(bad, out));
        bad = in; bad[0] = "mazewall-t4/1"; assert(!parse_t4_v2(bad, out)); assert(!parse_t4_v1(bad, out));
        bad = in; bad[0] = "mazewall-t4/3"; assert(!parse_t4_v2(bad, out));
        bad = in; bad[4] = "0"; assert(!parse_t4_v2(bad, out));
        bad = in; bad[6] = "/a/../b"; assert(!parse_t4_v2(bad, out));
        bad = in; bad[7] = "AB"; assert(!parse_t4_v2(bad, out));
        bad = in; bad[6] = std::string(4097, 'x'); assert(!parse_t4_v2(bad, out));
        bad = in; bad[7] = std::string(130, 'a'); assert(!parse_t4_v2(bad, out));
        bad = in; bad.push_back("extra"); assert(!parse_t4_v2(bad, out));
        bad = in; bad[2] = branch & 1 ? "zip-absent" : "zip-present"; assert(!parse_t4_v2(bad, out));
        bad = in; std::swap(bad[5], bad[8]); assert(!parse_t4_v2(bad, out));
        bad = in; bad[5 + 3 * 18] = bad[5]; assert(!parse_t4_v2(bad, out));
        assert(out.version == ProjectionVersion::T4V2 && out.roles == roles);
    }
}
