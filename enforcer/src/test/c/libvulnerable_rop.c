#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>

__attribute__((noinline))
void target_function() {
    printf("[C] HIJACKED: Control flow successfully hijacked to target_function!\n");
    fflush(stdout);
    exit(42);
}

__attribute__((noinline))
size_t exploit_me(const char *payload, size_t size) {
    volatile char buffer[16];
    if (payload == NULL) {
        // Calculate the exact offset from 'buffer' to the saved return address.
        // On x86_64, __builtin_frame_address(0) returns the saved frame pointer (RBP).
        // The return address is stored immediately after the saved RBP on the stack.
        void* rbp = __builtin_frame_address(0);
        void* ret_addr_ptr = (char*)rbp + 8;
        return (size_t)((char*)ret_addr_ptr - (char*)buffer);
    }
    // Perform stack buffer overflow
    memcpy((void*)buffer, payload, size);
    return 0;
}
