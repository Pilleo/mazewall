#include "../src/live_stack_checked_projection.cpp"

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <iostream>
#include <limits.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

extern "C" int __real_close(int);
extern "C" int __real_fstat(int, struct stat*);
namespace {
bool fail_close = false, fail_stat = false;
unsigned observed_closes = 0;
void require(bool value) { if (!value) { std::cerr << "protected image close test failed\n"; std::exit(1); } }
}
extern "C" int __wrap_close(int fd) {
    const int status = __real_close(fd);
    if (fail_close && status == 0) { ++observed_closes; errno = EIO; return -1; }
    return status;
}
extern "C" int __wrap_fstat(int fd, struct stat* st) {
    if (fail_stat) { errno = EACCES; return -1; }
    return __real_fstat(fd, st);
}
int main() {
    using namespace mazewall::live::jni;
    char executable[PATH_MAX];
    require(realpath("/proc/self/exe", executable) != nullptr);
    dl_phdr_info info{}; info.dlpi_name = executable;
    Deployment protected_image; protected_image.strict_close = true; protected_image.paths[0] = executable;
    fail_close = true; observed_closes = 0;
    require(image_callback(&info, sizeof(info), &protected_image) != 0);
    require(observed_closes == 1 && protected_image.inspection_error == EIO);
    Deployment legacy_image; legacy_image.paths[0] = executable;
    observed_closes = 0;
    require(image_callback(&info, sizeof(info), &legacy_image) == 0 && !legacy_image.images[0].path.empty());
    require(observed_closes == 1);
    protected_image.inspection_error = 0; fail_stat = true; observed_closes = 0;
    require(image_callback(&info, sizeof(info), &protected_image) != 0);
    require(protected_image.inspection_error == EACCES && observed_closes == 1);
    fail_stat = false;
    Deployment t4; t4.strict_close = true; t4.paths[0] = executable;
    T4ImageProbe probe{&t4}; observed_closes = 0;
    require(t4_image_callback(&info, sizeof(info), &probe) == 0 && probe.stage == 6 && probe.error == EIO);
    require(observed_closes == 1);
    fail_close = false;
    char path[] = "/tmp/mazewall-protected-map-XXXXXX";
    const int fd = mkstemp(path); require(fd >= 0);
    require(ftruncate(fd, 4096) == 0);
    struct stat identity{}; require(__real_fstat(fd, &identity) == 0);
    void* memory = mmap(nullptr, 4096, PROT_READ, MAP_PRIVATE, fd, 0); require(memory != MAP_FAILED);
    require(__real_close(fd) == 0);
    Deployment mapping; mapping.strict_close = true;
    Image image{}; image.file = identity;
    const auto address = reinterpret_cast<uintptr_t>(memory);
    mapping.mappings.push_back({address, address + 4096, static_cast<uint64_t>(identity.st_ino), 0, 0, true, false, false, {}});
    std::string problem;
    require(range(mapping, image, address, 1, false, &problem));
    fail_close = true; observed_closes = 0; problem.clear();
    require(!range(mapping, image, address, 1, false, &problem));
    require(observed_closes == 1 && problem.find("close failed") != std::string::npos);
    fail_stat = true; observed_closes = 0; problem.clear();
    require(!range(mapping, image, address, 1, false, &problem));
    require(observed_closes == 1 && problem.find("fstat failed") != std::string::npos);
    require(munmap(memory, 4096) == 0);
}
