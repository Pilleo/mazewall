#include "open_boundary_recorder.hpp"
#include "../src/agent.cpp"

#include <cassert>
#include <cstdlib>
#include <cstring>
#include <elf.h>
#include <fstream>
#include <iostream>
#include <iterator>
#include <vector>
#include <sys/stat.h>

namespace {
thread_local bool observe_boundary_ownership = false;
std::atomic<unsigned> boundary_allocations{0}, boundary_cleanup_registrations{0};
}

extern "C" void* __real__Znwm(std::size_t);
extern "C" void* __real__ZnwmRKSt9nothrow_t(std::size_t, const std::nothrow_t&) noexcept;
extern "C" void* __wrap__Znwm(std::size_t size) {
    if (observe_boundary_ownership) boundary_allocations.fetch_add(1, std::memory_order_relaxed);
    return __real__Znwm(size);
}
extern "C" void* __wrap__ZnwmRKSt9nothrow_t(std::size_t size, const std::nothrow_t& tag) noexcept {
    if (observe_boundary_ownership) boundary_allocations.fetch_add(1, std::memory_order_relaxed);
    return __real__ZnwmRKSt9nothrow_t(size, tag);
}

extern "C" int __real___cxa_thread_atexit(void (*)(void*), void*, void*);
extern "C" int __wrap___cxa_thread_atexit(void (*cleanup)(void*), void* object, void* dso) {
    if (observe_boundary_ownership) boundary_cleanup_registrations.fetch_add(1, std::memory_order_relaxed);
    return __real___cxa_thread_atexit(cleanup, object, dso);
}

using namespace mazewall::profiler;
namespace {
int clocks = 0, tids = 0, paths = 0;
bool fail_clock = false, fail_path = false;
bool fail_tid = false;
bool clock_read(std::uint64_t& time) noexcept {
    ++clocks;
    errno = EIO;
    time = 100 + clocks;
    return !fail_clock;
}
std::int64_t tid_read() noexcept { ++tids; errno = EBUSY; return fail_tid ? -1 : 73; }
bool path_read(std::uint64_t pointer, char* out, std::uint32_t& length) noexcept {
    ++paths;
    errno = EFAULT;
    if (fail_path || pointer == 0) return false;
    const auto* source = reinterpret_cast<const char*>(pointer);
    for (std::uint32_t i = 0; i < 256; ++i) {
        out[i] = source[i];
        if (source[i] == 0) { length = i + 1; return true; }
    }
    return false;
}
std::string directory;
std::vector<unsigned char> snapshot(OpenBoundaryRecorder& recorder, const char* name = "calls") {
    const auto destination = directory + "/" + name;
    assert(recorder.write(destination, 0x4d5a, true));
    std::ifstream file(destination, std::ios::binary);
    return {std::istreambuf_iterator<char>(file), {}};
}
std::uint64_t le(const std::vector<unsigned char>& bytes, std::size_t offset, int width = 8) {
    assert(offset + width <= bytes.size());
    std::uint64_t value = 0;
    for (int i = 0; i < width; ++i) value |= std::uint64_t(bytes[offset + i]) << (8 * i);
    return value;
}
OpenBoundaryInput input(OpenBoundaryRecorder& recorder, OpenBoundaryBridge& bridge, const char* path = "/fixture/input") {
    return recorder.prepare(bridge, reinterpret_cast<std::uint64_t>(path), 0x1234, 0600);
}
void disabled_has_no_effects() {
    clocks = tids = paths = 0;
    OpenBoundaryInput prepared{};
    int calls = 0;
    errno = EDOM;
    assert(invoke_open_boundary(nullptr, prepared, 9, 10, true, [&] {
        ++calls; assert(errno == EDOM); errno = ENOENT; return -1;
    }) == -1);
    assert(errno == ENOENT && calls == 1);
    assert(clocks == 0 && tids == 0 && paths == 0);
    assert(::access((directory + "/disabled").c_str(), F_OK) != 0);
}
void capacity_never_overwrites() {
    OpenBoundaryRecorder recorder(2, clock_read, tid_read, path_read);
    OpenBoundaryBridge bridge;
    auto prepared = input(recorder, bridge);
    auto a = recorder.begin(prepared, 0x1122334455667788ULL, 91, true);
    recorder.finish(a, 19);
    auto b = recorder.begin(prepared, 22, 92, true);
    recorder.finish(b, 20);
    auto c = recorder.begin(prepared, 33, 93, true);
    recorder.finish(c, 21);
    auto bytes = snapshot(recorder);
    std::cerr << "capacity_never_overwrites: expected records=2 actual=" << le(bytes, 40) << '\n';
    assert(le(bytes, 40) == 2);
    assert(bytes.size() == 896 && le(bytes, 24) == 3 && le(bytes, 32) == 3);
    assert(le(bytes, 48) == 2 && le(bytes, 56) == 1);
    assert(le(bytes, 136) == 0x1122334455667788ULL && le(bytes, 520) == 22);
    assert(c.slot == OpenBoundaryToken::invalid_slot);
}
void entry_without_return() {
    OpenBoundaryRecorder recorder(1, clock_read, tid_read, path_read);
    OpenBoundaryBridge bridge;
    recorder.begin(input(recorder, bridge), 8, 7, false);
    auto bytes = snapshot(recorder, "entry");
    assert(le(bytes, 40) == 1 && le(bytes, 48) == 0);
    assert(le(bytes, 128, 4) == 1 && le(bytes, 132, 4) == 8);
    assert(le(bytes, 184) == 0 && le(bytes, 216, 4) == 0);
}
void immutable_token_survives_reentry() {
    OpenBoundaryRecorder recorder(2, clock_read, tid_read, path_read);
    OpenBoundaryBridge bridge;
    auto prepared = input(recorder, bridge);
    const auto result = invoke_open_boundary(&recorder, prepared, 101, 91, true, [&] {
        return invoke_open_boundary(&recorder, prepared, 202, 92, true, [] { return 37; }) + 1;
    });
    assert(result == 38);
    auto bytes = snapshot(recorder, "nested");
    assert(le(bytes, 136) == 101 && le(bytes, 520) == 202);
    assert(le(bytes, 208, 4) == 38 && le(bytes, 592, 4) == 37);
    assert(le(bytes, 128, 4) == 3 && le(bytes, 512, 4) == 3);
}
void clock_and_path_failures_are_explicit() {
    OpenBoundaryRecorder recorder(3, clock_read, tid_read, path_read);
    OpenBoundaryBridge bridge;
    std::array<char, 257> unterminated; unterminated.fill('x');
    fail_clock = true;
    auto prepared = input(recorder, bridge, unterminated.data());
    auto token = recorder.begin(prepared, 1, 2, true);
    recorder.finish(token, -1);
    fail_clock = false;
    fail_path = true;
    prepared = input(recorder, bridge);
    recorder.begin(prepared, 3, 4, true);
    fail_path = false;
    fail_tid = true;
    OpenBoundaryBridge failed_bridge;
    prepared = input(recorder, failed_bridge, nullptr);
    recorder.begin(prepared, 0, 0, true);
    fail_tid = false;
    auto bytes = snapshot(recorder, "failed");
    assert((le(bytes, 132, 4) & 3) == 3 && (le(bytes, 516, 4) & 2) == 2);
    assert(le(bytes, 64) >= 1 && le(bytes, 72) == 3 && le(bytes, 80) == 1);
    assert((le(bytes, 900, 4) & 6) == 6);
    assert(le(bytes, 212, 4) == 0);
    for (int i = 224; i < 480; ++i) assert(bytes[i] == 0);
}
void errno_and_result_preserved() {
    OpenBoundaryRecorder recorder(1, clock_read, tid_read, path_read);
    OpenBoundaryBridge bridge;
    auto prepared = input(recorder, bridge);
    const auto tid_calls = tids;
    prepared = input(recorder, bridge);
    assert(tids == tid_calls && prepared.ordinal == 2);
    int calls = 0;
    errno = EDOM;
    auto result = invoke_open_boundary(&recorder, prepared, 1, 2, true, [&] {
        ++calls; assert(errno == EDOM); errno = ENOENT; return -1;
    });
    assert(calls == 1 && result == -1 && errno == ENOENT);
    auto bytes = snapshot(recorder, "errno");
    assert(le(bytes, 208, 4) == UINT32_MAX && le(bytes, 128, 4) == 3);
}
void serialized_layout_and_maximum() {
    OpenBoundaryRecorder recorder(1024, clock_read, tid_read, path_read);
    OpenBoundaryBridge bridge;
    for (int i = 0; i < 1024; ++i) {
        const auto prepared = input(recorder, bridge);
        recorder.finish(recorder.begin(prepared, i + 1, 0xabcdef, true), 42);
    }
    auto bytes = snapshot(recorder, "maximum");
    assert(bytes.size() == 393344);
    assert(std::memcmp(bytes.data(), "MZOB", 4) == 0);
    assert(le(bytes, 4, 2) == 1 && le(bytes, 6, 2) == 128);
    assert(le(bytes, 8, 4) == 384 && le(bytes, 12, 4) == 1024);
    assert(le(bytes, 16) == 0x4d5a && le(bytes, 88) == 1 && le(bytes, 96) == unsigned(::getpid()));
    for (int i = 104; i < 128; ++i) assert(bytes[i] == 0);
    assert(le(bytes, 144) == 1 && le(bytes, 152, 4) == unsigned(::getpid()) && le(bytes, 156, 4) == 73);
    assert(le(bytes, 160) <= le(bytes, 168) && le(bytes, 168) <= le(bytes, 176));
    assert(le(bytes, 176) <= le(bytes, 184));
    assert(le(bytes, 200, 4) == 0x1234 && le(bytes, 204, 4) == 0600);
    assert(le(bytes, 212, 4) == 15 && le(bytes, 216, 4) == 1 && le(bytes, 220, 4) == 0);
    assert(std::memcmp(bytes.data() + 224, "/fixture/input", 15) == 0);
    assert(le(bytes, 480) == 0xabcdef);
    for (int i = 488; i < 512; ++i) assert(bytes[i] == 0);
    struct stat st{}; assert(::stat((directory + "/maximum").c_str(), &st) == 0);
    assert((st.st_mode & 0777) == 0600);
    assert(!recorder.write(directory + "/maximum", 0x4d5a, true));
    assert(::stat((directory + "/maximum.tmp").c_str(), &st) == 0 && st.st_size == 393344);
    assert(::symlink((directory + "/maximum").c_str(), (directory + "/symlink.tmp").c_str()) == 0);
    assert(!recorder.write(directory + "/symlink", 0x4d5a, true));
    assert(::access((directory + "/symlink").c_str(), F_OK) != 0);
    assert(::stat((directory + "/maximum").c_str(), &st) == 0 && st.st_size == 393344);
}

void bridge_exhaustion_has_no_thread_owners() {
    open_boundary_recorder = std::make_unique<OpenBoundaryRecorder>(1024, clock_read, tid_read, path_read);
    unsigned original_calls = 0;
    for (unsigned i = 0; i < 272; ++i) {
        std::thread participant([&] {
            observe_boundary_ownership = true;
            errno = EDOM;
            const auto prepared = prepare_open_boundary(reinterpret_cast<jlong>("/fixture/input"), 0, 0);
            assert(errno == EDOM);
            const auto result = invoke_open_boundary(open_boundary_recorder.get(), prepared, i + 1, 9, true, [&] {
                ++original_calls; assert(errno == EDOM); errno = ENOENT; return -1;
            });
            assert(result == -1 && errno == ENOENT);
            observe_boundary_ownership = false;
        });
        participant.join();
        if (i == 255) {
            std::cerr << "admitted threads=256 allocations=" << boundary_allocations.load()
                      << " cleanup-registrations=" << boundary_cleanup_registrations.load() << '\n';
            assert(boundary_allocations.load() == 0 && boundary_cleanup_registrations.load() == 0);
            boundary_allocations.store(0);
            boundary_cleanup_registrations.store(0);
        }
    }
    const auto bytes = snapshot(*open_boundary_recorder, "exhaustion");
    assert(original_calls == 272 && le(bytes, 40) == 272 && le(bytes, 48) == 272 && le(bytes, 80) == 16);
    for (unsigned i = 0; i < 272; ++i) {
        const auto offset = 128 + i * 384;
        assert(le(bytes, offset, 4) == 3 && le(bytes, offset + 80, 4) == UINT32_MAX);
        assert(le(bytes, offset + 8) == i + 1);
        assert(le(bytes, offset + 4, 4) == (i < 256 ? 0 : 6));
    }
    std::cerr << "rejected threads=16 original-calls=" << original_calls
              << " allocations=" << boundary_allocations.load()
              << " cleanup-registrations=" << boundary_cleanup_registrations.load() << '\n';
    assert(boundary_allocations.load() == 0 && boundary_cleanup_registrations.load() == 0);
    std::cerr << "recorder-bytes=" << sizeof(OpenBoundaryRecorder)
              << " fixed-bridge-bytes=" << 256 * sizeof(OpenBoundaryBridge) << '\n';
    open_boundary_recorder.reset();
}

void diagnostic_symbols_are_not_exported(const char* library) {
    std::ifstream file(library, std::ios::binary);
    const std::vector<unsigned char> bytes{std::istreambuf_iterator<char>(file), {}};
    assert(bytes.size() >= sizeof(Elf64_Ehdr));
    Elf64_Ehdr header{}; std::memcpy(&header, bytes.data(), sizeof(header));
    assert(std::memcmp(header.e_ident, ELFMAG, SELFMAG) == 0 && header.e_ident[EI_CLASS] == ELFCLASS64);
    assert(header.e_shentsize == sizeof(Elf64_Shdr));
    auto section = [&](unsigned index) {
        const auto offset = header.e_shoff + index * sizeof(Elf64_Shdr);
        assert(offset + sizeof(Elf64_Shdr) <= bytes.size());
        Elf64_Shdr value{}; std::memcpy(&value, bytes.data() + offset, sizeof(value)); return value;
    };
    std::unordered_map<std::string, bool> required{
        {"Agent_OnLoad", false}, {"Agent_OnUnload", false},
        {"mazewall_stack_marker", false}, {"mazewall_stack_marker_v2", false},
        {"mazewall_symbolization_replacement_begin", false}, {"mazewall_symbolization_replacement_end", false}};
    unsigned diagnostic_exports = 0;
    for (unsigned i = 0; i < header.e_shnum; ++i) {
        const auto symbols = section(i);
        if (symbols.sh_type != SHT_DYNSYM) continue;
        const auto strings = section(symbols.sh_link);
        assert(strings.sh_offset + strings.sh_size <= bytes.size());
        assert(symbols.sh_offset + symbols.sh_size <= bytes.size() && symbols.sh_entsize == sizeof(Elf64_Sym));
        for (std::size_t offset = 0; offset < symbols.sh_size; offset += sizeof(Elf64_Sym)) {
            Elf64_Sym symbol{}; std::memcpy(&symbol, bytes.data() + symbols.sh_offset + offset, sizeof(symbol));
            if (symbol.st_shndx == SHN_UNDEF) continue;
            assert(symbol.st_name < strings.sh_size);
            const auto* name = reinterpret_cast<const char*>(bytes.data() + strings.sh_offset + symbol.st_name);
            assert(std::memchr(name, 0, strings.sh_size - symbol.st_name) != nullptr);
            if (std::strstr(name, "OpenBoundary") || std::strstr(name, "invoke_open_boundary")) {
                ++diagnostic_exports; std::cerr << "unexpected diagnostic export: " << name << '\n';
            }
            if (auto found = required.find(name); found != required.end()) found->second = true;
        }
    }
    for (const auto& [name, present] : required) { (void)name; assert(present); }
    std::cerr << "required exports=6 diagnostic exports=" << diagnostic_exports << '\n';
    assert(diagnostic_exports == 0);
}
}
int main(int argc, char** argv) {
    if (argc == 3 && std::string(argv[1]) == "--exports") {
        diagnostic_symbols_are_not_exported(argv[2]);
        return 0;
    }
    char pattern[] = "/tmp/opencode/a4-open-recorder-XXXXXX";
    const auto* created = ::mkdtemp(pattern); assert(created); directory = created;
    disabled_has_no_effects();
    capacity_never_overwrites();
    entry_without_return();
    immutable_token_survives_reentry();
    clock_and_path_failures_are_explicit();
    errno_and_result_preserved();
    serialized_layout_and_maximum();
    bridge_exhaustion_has_no_thread_owners();
    std::cout << "open_boundary_recorder_test: 8 cases passed\n";
}
