#include <dlfcn.h>

int main(int argc, char** argv) {
    if (argc != 2) return 1;
    void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) return 2;
    const auto marker = dlsym(library, "mazewall_stack_marker");
    if (marker == nullptr) return 3;
    const auto marker_v2 = dlsym(library, "mazewall_stack_marker_v2");
    if (marker_v2 == nullptr) return 4;
    dlclose(library);
}
