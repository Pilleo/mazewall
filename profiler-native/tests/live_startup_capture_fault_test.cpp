#include "live_startup_binding.hpp"
#include "private_sha256.hpp"

#include <algorithm>
#include <array>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <map>
#include <new>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

using namespace mazewall::profiler::integrity;
extern "C" int __real_open(const char*, int, ...);
extern "C" int __real_openat(int, const char*, int, ...);
extern "C" int __real_close(int);
extern "C" int __real_fstat(int, struct stat*);
extern "C" ssize_t __real_read(int, void*, std::size_t);
namespace {
enum class Kind { Directory, LiveDirectory, Binding, Manifest, Cmdline };
std::string root;
struct Ownership { Kind kind; unsigned generation; };
std::map<int, Ownership> owned;
std::vector<unsigned> physically_closed;
unsigned next_generation = 0;
bool interrupt_read = false, change_file = false, fail_close = false, missing = false, throw_read = false;
unsigned binding_stats = 0;
void require(bool value) { if (!value) { std::cerr << "capture fault test failed\n"; std::exit(1); } }
int track(int fd, Kind kind) { if (fd >= 0) require(owned.emplace(fd, Ownership{kind, ++next_generation}).second); return fd; }
std::string hex(const std::array<std::uint8_t, 32>& bytes) {
    constexpr char chars[] = "0123456789abcdef";
    std::string out;
    for (auto byte : bytes) { out.push_back(chars[byte >> 4]); out.push_back(chars[byte & 15]); }
    return out;
}
void reset() {
    require(owned.empty()); binding_stats = 0; physically_closed.clear(); next_generation = 0;
    interrupt_read = change_file = fail_close = missing = throw_read = false;
}
void write(const std::filesystem::path& path, const std::string& bytes) {
    std::ofstream file(path, std::ios::binary | std::ios::out); require(file.good());
    file.write(bytes.data(), static_cast<std::streamsize>(bytes.size())); require(file.good());
}
}
extern "C" int __wrap_open(const char* path, int flags, ...) {
    if (std::strcmp(path, "/") == 0) {
        require((flags & (O_DIRECTORY | O_NOFOLLOW)) == (O_DIRECTORY | O_NOFOLLOW));
        return track(__real_open(root.c_str(), flags), Kind::Directory);
    }
    if (std::strcmp(path, "/proc/self/cmdline") == 0)
        return track(__real_open((root + "/cmdline").c_str(), flags), Kind::Cmdline);
    errno = ENOENT; return -1;
}
extern "C" int __wrap_openat(int fd, const char* path, int flags, ...) {
    require(owned.count(fd) != 0);
    if (std::strcmp(path, "startup-binding.v1") == 0 && missing) { errno = ENOENT; return -1; }
    Kind kind = Kind::Directory;
    if (std::strcmp(path, "live") == 0) kind = Kind::LiveDirectory;
    if (std::strcmp(path, "startup-binding.v1") == 0) kind = Kind::Binding;
    if (std::strcmp(path, "distribution.v1") == 0) kind = Kind::Manifest;
    if (kind == Kind::Binding || kind == Kind::Manifest)
        require((flags & (O_NOFOLLOW | O_NONBLOCK)) == (O_NOFOLLOW | O_NONBLOCK));
    return track(__real_openat(fd, path, flags), kind);
}
extern "C" int __wrap_close(int fd) {
    const auto it = owned.find(fd); require(it != owned.end());
    const auto acquisition = it->second; owned.erase(it);
    const int result = __real_close(fd);
    if (result == 0) {
        require(std::find(physically_closed.begin(), physically_closed.end(), acquisition.generation) == physically_closed.end());
        physically_closed.push_back(acquisition.generation);
    }
    if (result == 0 && fail_close && acquisition.kind == Kind::LiveDirectory) { errno = EIO; return -1; }
    return result;
}
extern "C" int __wrap_fstat(int fd, struct stat* st) {
    const int result = __real_fstat(fd, st);
    if (result == 0 && owned.at(fd).kind == Kind::Binding && ++binding_stats == 2 && change_file) ++st->st_size;
    return result;
}
extern "C" ssize_t __wrap_read(int fd, void* buffer, std::size_t size) {
    require(owned.count(fd) != 0);
    if (throw_read) { throw_read = false; throw std::bad_alloc(); }
    if (interrupt_read) { interrupt_read = false; errno = EINTR; return -1; }
    return __real_read(fd, buffer, size);
}
int main(int argc, char** argv) {
    require(argc == 3);
    std::ifstream file(argv[1]); require(file.good());
    const std::string manifest{std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
    Sha256 sha; std::array<std::uint8_t, 32> digest{};
    require(sha.update(reinterpret_cast<const std::uint8_t*>(manifest.data()), manifest.size()) && sha.finish(digest));
    const auto binding = "MAZEWALL-BINDING\t1\nMANIFEST\tL2V0Yy9tYXpld2FsbC9saXZlL2Rpc3RyaWJ1dGlvbi52MQ\t" + hex(digest) + "\nEND\n";
    std::string pattern = std::string(argv[2]) + "/startup-fixture-XXXXXX";
    require(::mkdtemp(pattern.data()) != nullptr); root = pattern;
    const auto live = std::filesystem::path(root) / "etc/mazewall/live";
    require(std::filesystem::create_directories(live));
    write(live / "startup-binding.v1", binding); write(live / "distribution.v1", manifest);
    Manifest parsed;
    require(parse_manifest(manifest, parsed));
    std::string cmdline;
    for (const auto& arg : parsed.process_args) { cmdline += arg; cmdline.push_back('\0'); }
    write(std::filesystem::path(root) / "cmdline", cmdline);
    StartupSnapshot snapshot;
    reset(); interrupt_read = true;
    require(capture_startup(snapshot).disposition == CaptureDisposition::Ready); require(owned.empty());
    reset(); missing = true;
    require(capture_startup(snapshot).disposition == CaptureDisposition::Absent); require(owned.empty());
    reset(); missing = fail_close = true;
    require(capture_startup(snapshot).disposition == CaptureDisposition::Invalid); require(owned.empty());
    reset(); change_file = true;
    require(capture_startup(snapshot).disposition == CaptureDisposition::Invalid); require(owned.empty());
    reset(); throw_read = true;
    try { (void) capture_startup(snapshot); require(false); }
    catch (const std::bad_alloc&) { require(owned.empty()); }
    cmdline.push_back('\0');
    write(std::filesystem::path(root) / "cmdline", cmdline);
    reset(); require(capture_startup(snapshot).disposition == CaptureDisposition::Invalid); require(owned.empty());
}
