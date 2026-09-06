#include <dlfcn.h>

int main(int argc, char** argv) {
    if (argc != 2) return 1;
    void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) return 2;
    const auto marker = dlsym(library, "mazewall_stack_marker");
    if (marker == nullptr) return 3;
    dlclose(library);
}
