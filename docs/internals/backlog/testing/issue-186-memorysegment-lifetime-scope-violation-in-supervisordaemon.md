---
title: "MemorySegment Lifetime/Scope Violation in SupervisorSessionHandler"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "ffi"
effort: "medium"
---

# 🔴 [Severity: HIGH]: MemorySegment Lifetime/Scope Violation in SupervisorSessionHandler
**Context:** In `SupervisorSessionHandler.kt`, inside the `openFileInSupervisor` function, a `MemorySegment` pointing to the file path is allocated using `arena.allocateFrom(pathStr)`. This string segment is scoped to the temporary `Arena.ofConfined().use { ... }` block. If the system call `LinuxNative.syscall(LinuxSys, ..., NativeArg.MemoryArg(pathSeg), ...)` were an asynchronous or non-blocking operation (such as io_uring), the kernel might access `pathSeg` after the arena has already been closed. While `openat` is blocking, this pattern violates safe FFM ABI practices because it relies on the implicit behavior of the syscall rather than strictly enforcing lifetimes.
**Needed:** Ensure `MemorySegment` lifecycle in `openFileInSupervisor` aligns safely with the `NativeArg` and execution boundaries.
