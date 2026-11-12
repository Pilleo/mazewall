#include "live_startup_binding.hpp"
#include "private_sha256.hpp"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdint>
#include <fcntl.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace mazewall::profiler::integrity {
namespace {
std::unique_ptr<const StartupSnapshot> installed_snapshot;
struct ReadResult { std::string bytes; int error = 0; bool absent = false; };
int close_once(int fd, int prior) {
    if (::close(fd) == 0) return prior;
    const int failure = errno;
    return prior ? prior : failure;
}
ReadResult read_fixed(const char* leaf, std::size_t limit) {
    ReadResult result;
    int directory = ::open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (directory < 0) { result.error = errno; return result; }
    for (const char* component : {"etc", "mazewall", "live"}) {
        const int next = ::openat(directory, component, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        const int opened_error = next < 0 ? errno : 0;
        const int close_error = close_once(directory, 0);
        const int failure = opened_error ? opened_error : close_error;
        if (failure) {
            result.absent = opened_error == ENOENT && !close_error;
            if (next >= 0) close_once(next, failure);
            result.error = failure;
            return result;
        }
        directory = next;
    }
    const int fd = ::openat(directory, leaf, O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
    const int opened_error = fd < 0 ? errno : 0;
    const int closed_error = close_once(directory, 0);
    const int directory_error = opened_error ? opened_error : closed_error;
    if (directory_error) {
        result.absent = opened_error == ENOENT && !closed_error;
        if (fd >= 0) close_once(fd, directory_error);
        result.error = directory_error;
        return result;
    }
    try {
    struct stat before{};
    if (::fstat(fd, &before) != 0) result.error = errno;
    else if (!S_ISREG(before.st_mode) || before.st_size < 0 || static_cast<std::uint64_t>(before.st_size) > limit) result.error = EINVAL;
    if (!result.error) {
        result.bytes.resize(static_cast<std::size_t>(before.st_size));
        std::size_t offset = 0;
        while (offset < result.bytes.size()) {
            const auto n = ::read(fd, result.bytes.data() + offset, result.bytes.size() - offset);
            if (n < 0) { const int failure = errno; if (failure == EINTR) continue; result.error = failure; break; }
            if (n == 0) { result.error = EIO; break; }
            offset += static_cast<std::size_t>(n);
        }
        char extra;
        if (!result.error) {
            ssize_t n;
            do { n = ::read(fd, &extra, 1); } while (n < 0 && errno == EINTR);
            if (n < 0) result.error = errno;
            else if (n != 0) result.error = EIO;
        }
        struct stat after{};
        if (!result.error && ::fstat(fd, &after) != 0) result.error = errno;
        if (!result.error && (before.st_dev != after.st_dev || before.st_ino != after.st_ino
                || before.st_size != after.st_size || before.st_mtim.tv_sec != after.st_mtim.tv_sec
                || before.st_mtim.tv_nsec != after.st_mtim.tv_nsec || before.st_ctim.tv_sec != after.st_ctim.tv_sec
                || before.st_ctim.tv_nsec != after.st_ctim.tv_nsec)) result.error = EIO;
    }
    result.error = close_once(fd, result.error);
    return result;
    } catch (...) {
        close_once(fd, 0);
        throw;
    }
}
ReadResult process_cmdline() {
    ReadResult result;
    const int fd = ::open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) { result.error = errno; return result; }
    try {
    std::array<char, 4096> block{};
    while (true) {
        const auto n = ::read(fd, block.data(), block.size());
        if (n < 0) { const int failure = errno; if (failure == EINTR) continue; result.error = failure; break; }
        if (!n) break;
        if (static_cast<std::size_t>(n) > 262144 - result.bytes.size()) { result.error = E2BIG; break; }
        result.bytes.append(block.data(), static_cast<std::size_t>(n));
    }
    result.error = close_once(fd, result.error);
    return result;
    } catch (...) {
        close_once(fd, 0);
        throw;
    }
}
bool supported_launch(const Manifest& manifest) {
    if (manifest.artifacts.size() != 30 || manifest.jvm_args.size() != 9
            || manifest.process_args.size() < manifest.jvm_args.size() + 4
            || manifest.process_args.size() > 128
            || manifest.process_args[0] != manifest.artifacts[static_cast<std::size_t>(ArtifactRole::Java)].path) return false;
    constexpr std::string_view log_prefix = "-Xlog:class+load=info:file=";
    const auto& log = manifest.jvm_args[0];
    if (!log.starts_with(log_prefix)) return false;
    const auto path = std::string_view(log).substr(log_prefix.size());
    if (path.size() < 2 || path.front() != '/' || path.back() == '/' || path.find("//") != path.npos) return false;
    std::size_t start = 1;
    while (start < path.size()) {
        auto end = path.find('/', start);
        if (end == path.npos) end = path.size();
        const auto part = path.substr(start, end - start);
        if (part == "." || part == "..") return false;
        for (char c : part) {
            const bool allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!allowed) return false;
        }
        start = end + 1;
    }
    const auto& agent_path = manifest.artifacts[static_cast<std::size_t>(ArtifactRole::Agent)].path;
    if (agent_path.find('=') != std::string::npos) return false;
    const auto expected_agent = "-agentpath:" + agent_path + "=live-stack=only";
    const std::array<std::string, 8> expected{
        "-XX:+DisableAttachMechanism", "-XX:-EnableDynamicAgentLoading", "-Xshare:off", "-XX:+UnlockExperimentalVMOptions",
        "-XX:-EnableJVMCI", "--enable-native-access=ALL-UNNAMED", "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED", expected_agent};
    if (manifest.process_args[1] != log) return false;
    for (std::size_t i = 0; i < expected.size(); ++i) {
        if (manifest.jvm_args[i + 1] != expected[i] || manifest.process_args[i + 2] != expected[i]) return false;
    }
    const auto cp_index = manifest.jvm_args.size() + 1;
    if (manifest.process_args[cp_index] != "-cp") return false;
    std::string classpath;
    for (std::size_t i = 23; i < 30; ++i) {
        const auto& entry = manifest.artifacts[i].path;
        if (entry.find(':') != std::string::npos || entry.find('*') != std::string::npos) return false;
        if (!classpath.empty()) classpath.push_back(':');
        classpath += entry;
    }
    if (manifest.process_args[cp_index + 1] != classpath) return false;
    const auto& main = manifest.process_args[cp_index + 2];
    if (main.empty()) return false;
    bool component_start = true;
    for (char c : main) {
        if (c == '.') { if (component_start) return false; component_start = true; continue; }
        const bool letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '$';
        if (!letter && (component_start || c < '0' || c > '9')) return false;
        component_start = false;
    }
    if (component_start) return false;
    for (std::size_t i = cp_index + 3; i < manifest.process_args.size(); ++i)
        if (manifest.process_args[i].empty() || manifest.process_args[i][0] == '@') return false;
    return true;
}
std::string digest(const std::string& input) {
    Sha256 sha;
    std::array<std::uint8_t, 32> bytes{};
    if (!sha.update(reinterpret_cast<const std::uint8_t*>(input.data()), input.size()) || !sha.finish(bytes)) return {};
    constexpr char alphabet[] = "0123456789abcdef";
    std::string hex;
    hex.reserve(64);
    for (auto b : bytes) { hex.push_back(alphabet[b >> 4]); hex.push_back(alphabet[b & 15]); }
    return hex;
}
}
bool supports_protected_launch(const Manifest& manifest) { return supported_launch(manifest); }
CaptureResult capture_startup(StartupSnapshot& result) {
    const auto binding_bytes = read_fixed("startup-binding.v1", 16384);
    if (binding_bytes.absent) return {CaptureDisposition::Absent, 0};
    if (binding_bytes.error) return {CaptureDisposition::Invalid, binding_bytes.error};
    StartupSnapshot candidate;
    if (!parse_binding(binding_bytes.bytes, candidate.binding)) return {CaptureDisposition::Invalid, EINVAL};
    const auto manifest_bytes = read_fixed("distribution.v1", 262144);
    if (manifest_bytes.error) return {CaptureDisposition::Invalid, manifest_bytes.error};
    if (digest(manifest_bytes.bytes) != candidate.binding.manifest_sha256
            || !parse_manifest(manifest_bytes.bytes, candidate.manifest)) return {CaptureDisposition::Invalid, EINVAL};
    const auto process = process_cmdline();
    if (process.error) return {CaptureDisposition::Invalid, process.error};
    if (process.bytes.empty() || process.bytes.back() != '\0') return {CaptureDisposition::Invalid, EINVAL};
    std::vector<std::string> args;
    std::size_t offset = 0;
    while (offset < process.bytes.size()) {
        const auto end = process.bytes.find('\0', offset);
        if (end == std::string::npos || end == offset || end - offset > 4096 || args.size() >= 128)
            return {CaptureDisposition::Invalid, EINVAL};
        args.push_back(process.bytes.substr(offset, end - offset));
        offset = end + 1;
    }
    if (args != candidate.manifest.process_args || !supports_protected_launch(candidate.manifest))
        return {CaptureDisposition::Invalid, EINVAL};
    result = std::move(candidate);
    return {CaptureDisposition::Ready, 0};
}
namespace {
class OwnedFd final {
public:
    explicit OwnedFd(int fd) noexcept : fd_(fd) {}
    ~OwnedFd() { if (fd_ >= 0) (void)::close(fd_); }
    OwnedFd(const OwnedFd&) = delete;
    OwnedFd& operator=(const OwnedFd&) = delete;
    int get() const noexcept { return fd_; }
    int release() noexcept { return std::exchange(fd_, -1); }
    void take(int fd) noexcept { fd_ = fd; }
    bool close() noexcept {
        const int fd = std::exchange(fd_, -1);
        if (fd < 0) return true;
        const int result = ::close(fd);
        const int failure = result == 0 ? 0 : errno;
        return failure == 0;
    }
private:
    int fd_;
};
bool measure_artifact(const Artifact& artifact, struct stat& identity) {
    OwnedFd directory(::open("/", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW));
    if (directory.get() < 0) return false;
    std::size_t start = 1;
    while (true) {
        const auto slash = artifact.path.find('/', start);
        const bool last = slash == std::string::npos;
        const auto name = artifact.path.substr(start, last ? std::string::npos : slash - start);
        const int flags = last ? O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW
                               : O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW;
        const int next = ::openat(directory.get(), name.c_str(), flags);
        const int open_error = next < 0 ? errno : 0;
        OwnedFd acquired(next);
        if (!directory.close() || open_error) return false;
        if (!last) {
            directory.take(acquired.release());
            start = slash + 1;
            continue;
        }
        struct stat before{};
        if (::fstat(acquired.get(), &before) != 0 || !S_ISREG(before.st_mode)
                || !artifact_size_allowed(before.st_size)) return false;
        Sha256 sha;
        std::array<std::uint8_t, 65536> block{};
        std::uint64_t total = 0;
        while (true) {
            const auto n = ::read(acquired.get(), block.data(), block.size());
            if (n < 0) { const int failure = errno; if (failure == EINTR) continue; return false; }
            if (n == 0) break;
            if (static_cast<std::uint64_t>(n) > (std::uint64_t{1} << 30) - total
                    || !sha.update(block.data(), static_cast<std::size_t>(n))) return false;
            total += static_cast<std::uint64_t>(n);
        }
        struct stat after{};
        if (::fstat(acquired.get(), &after) != 0 || total != static_cast<std::uint64_t>(before.st_size)
                || before.st_dev != after.st_dev || before.st_ino != after.st_ino || before.st_size != after.st_size
                || before.st_mtim.tv_sec != after.st_mtim.tv_sec || before.st_mtim.tv_nsec != after.st_mtim.tv_nsec
                || before.st_ctim.tv_sec != after.st_ctim.tv_sec || before.st_ctim.tv_nsec != after.st_ctim.tv_nsec) return false;
        std::array<std::uint8_t, 32> hash{};
        if (!sha.finish(hash)) return false;
        constexpr char alphabet[] = "0123456789abcdef";
        std::string actual;
        actual.reserve(64);
        for (auto byte : hash) { actual.push_back(alphabet[byte >> 4]); actual.push_back(alphabet[byte & 15]); }
        if (actual != artifact.sha256 || !acquired.close()) return false;
        identity = after;
        return true;
    }
}
}
bool artifact_size_allowed(std::int64_t size) noexcept {
    return size >= 0 && static_cast<std::uint64_t>(size) <= (std::uint64_t{1} << 30);
}
bool verify_artifacts(const Manifest& manifest, std::vector<FileIdentity>& measured) {
    if (manifest.artifacts.size() != 30) return false;
    constexpr std::array<std::pair<ArtifactRole, std::string_view>, 8> fixed{{
        {ArtifactRole::Java, "1bf1aaef01e4f8d7c85d913c60fb2ee4624cbe54d4053254c59657b7ea913b25"},
        {ArtifactRole::Release, "8545053a54a73969a65db2c8157f40f89bcdcf7526ae90a7ce9b3061c01bc34a"},
        {ArtifactRole::Modules, "f5463cffa81c51b3c13f57e2c23eacceab2b12e842069acf2facd5485f63bc85"},
        {ArtifactRole::Libjvm, "30b96b2f2b69f313f4c85d9b437511a1d97e64c29a3d13b0abd6b07c7f50335b"},
        {ArtifactRole::Libjava, "af54a944d819ebfdec651a9b6b77dc7a4126c871f6b74dcf1fd69aa8edd17053"},
        {ArtifactRole::Libnio, "1db89f7ef9251543c9bf46c6355046d8ba8b6620ed908c7f9487e52db3ecee08"},
        {ArtifactRole::Libc, "85e64f97e348786a8fb4d9f3d52fec289e2fb86bba20f0731dfe61990525e0f7"},
        {ArtifactRole::Loader, "bda779ea9e9ad234f60315477d7cfd0bdde861cd8ddd9df8cdaf62f18c4cec14"}}};
    for (const auto& [role, expected] : fixed) {
        const auto index = static_cast<std::size_t>(role);
        if (manifest.artifacts[index].role != role || manifest.artifacts[index].sha256 != expected) return false;
    }
    std::vector<std::pair<dev_t, ino_t>> identities;
    std::vector<FileIdentity> candidate;
    identities.reserve(manifest.artifacts.size());
    candidate.reserve(manifest.artifacts.size());
    for (std::size_t i = 0; i < manifest.artifacts.size(); ++i) {
        const auto& artifact = manifest.artifacts[i];
        if (artifact.role != static_cast<ArtifactRole>(i)) return false;
        struct stat identity{};
        if (!measure_artifact(artifact, identity)) return false;
        const auto key = std::make_pair(identity.st_dev, identity.st_ino);
        if (std::find(identities.begin(), identities.end(), key) != identities.end()) return false;
        identities.push_back(key);
        candidate.push_back(FileIdentity{static_cast<std::uint64_t>(identity.st_dev), static_cast<std::uint64_t>(identity.st_ino)});
    }
    measured = std::move(candidate);
    return true;
}
void install_startup_snapshot(StartupSnapshot&& snapshot) {
    if (installed_snapshot) throw std::logic_error("startup snapshot already installed");
    installed_snapshot = std::make_unique<const StartupSnapshot>(std::move(snapshot));
}
const StartupSnapshot* startup_snapshot() noexcept {
    return installed_snapshot.get();
}

}
