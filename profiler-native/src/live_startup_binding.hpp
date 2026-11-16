#pragma once

#include <array>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace mazewall::profiler::integrity {
struct Binding final { std::string manifest_sha256; };
enum class ArtifactRole {
    Agent, Java, Libjvm, Libjava, Libnio, Libc, Loader, Libstdcpp, Libgcc, Libm, Libdl, Libpthread, Librt,
    Libnet, Libjli, Libjimage, Libmanagement, LibmanagementExt, Libsyslookup, Libzip, Libsimdsort,
    Release, Modules, Helper, Enforcer, KotlinStdlib, SerializationCore, SerializationJson, Annotations, ManagedFixture
};
struct Artifact final {
    ArtifactRole role;
    std::string path;
    std::string sha256;
    std::string build_id;
    std::string provenance;
};
struct Witness final { std::string name; ArtifactRole role; };
struct Manifest final {
    std::string source;
    std::array<std::string, 5> runtime;
    std::vector<std::string> process_args;
    std::vector<std::string> jvm_args;
    std::vector<Artifact> artifacts;
    std::vector<Witness> witnesses;
    std::array<bool, 2> conditional_images{};
};
struct StartupSnapshot final { Binding binding; Manifest manifest; };
enum class CaptureDisposition { Absent, Invalid, Ready };
struct CaptureResult final { CaptureDisposition disposition; int error; };
CaptureResult capture_startup(StartupSnapshot& result);
void install_startup_snapshot(StartupSnapshot&& snapshot);
const StartupSnapshot* startup_snapshot() noexcept;
struct FileIdentity final { std::uint64_t device; std::uint64_t inode; };
bool supports_protected_launch(const Manifest& manifest);
bool artifact_size_allowed(std::int64_t size) noexcept;
bool verify_artifacts(const Manifest& manifest, std::vector<FileIdentity>& identities);
bool parse_manifest(std::string_view bytes, Manifest& result);
// Results are assigned only after the complete canonical input is valid.
bool parse_binding(std::string_view bytes, Binding& result);
}
