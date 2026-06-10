# Skill: FFM Safety

This skill provides a rigorous checklist for using the JDK Foreign Function & Memory (FFM) API safely in `mazewall`.

## Checklist

### 1. Memory Lifecycle
- [ ] **Arena Management:** Always use `Arena.ofConfined()` within a `use { ... }` block (or `try-with-resources`) to ensure deterministic deallocation.
- [ ] **Thread Confinement:** Never share a `Confined` memory segment across threads. If cross-thread access is needed, use `Arena.ofShared()`.
- [ ] **Allocation:** Prefer `arena.allocate(layout)` over raw `MemorySegment.ofAddress` to ensure the segment is bound to a lifecycle.

### 2. Native Downcalls & Errno
- [ ] **Capture State:** All native downcalls that can set `errno` MUST use `Linker.Option.captureCallState("errno")`.
- [ ] **Timing:** Read `errno` from the captured state segment **immediately** after the `invoke` call. 
- [ ] **No Interference:** Ensure no other FFM calls or JNI calls occur between the downcall and the `errno` read, as the capture state is sensitive to thread-local interference in some environments.

### 3. Layouts & Alignment
- [ ] **Correct ValueLayout:** 
    - Use `JAVA_INT` for 32-bit `int` fields (e.g., `seccomp_data` fields).
    - Use `JAVA_LONG` for 64-bit `long` fields.
    - Use `ADDRESS` for pointers.
- [ ] **Byte Order:** Ensure the `ByteOrder` of the layout matches the native platform (usually `ByteOrder.nativeOrder()`).
- [ ] **Padding:** Explicitly define padding in `StructLayout` to match C struct alignment rules.

### 4. Verification
- [ ] **Unit Tests:** Verify the layout size and field offsets against known constants (e.g., from `man` pages or `sizeof` in C).
- [ ] **Address Validation:** Always check if a returned `MemorySegment` is `NULL` (address 0) before dereferencing.
