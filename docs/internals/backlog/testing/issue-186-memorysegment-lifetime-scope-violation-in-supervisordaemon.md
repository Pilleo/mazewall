---
title: "MemorySegment Lifetime/Scope Violation in SupervisorSessionHandler"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "ffi"
effort: "medium"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: MemorySegment Lifetime/Scope Violation in SupervisorSessionHandler

**Context:**
In `SupervisorSessionHandler.kt`, inside the `openFileInSupervisor` function, a `MemorySegment` pointing to the file path is allocated using `arena.allocateFrom(pathStr)`. This string segment is scoped to the temporary `Arena.ofConfined().use { ... }` block. If the system call `LinuxNative.syscall(LinuxSys, ..., NativeArg.MemoryArg(pathSeg), ...)` were an asynchronous or non-blocking operation (such as io_uring), the kernel might access `pathSeg` after the arena has already been closed. While `openat` is blocking, this pattern violates safe FFM ABI practices because it relies on the implicit behavior of the syscall rather than strictly enforcing lifetimes.


**Needed:**
1. Implement a fix based on the issue description.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: `Unknown`
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
