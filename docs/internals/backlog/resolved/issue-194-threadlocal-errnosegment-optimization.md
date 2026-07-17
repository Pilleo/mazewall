---
title: "Global/ThreadLocal ErrnoSegment Optimization"
severity: "HIGH"
status: "resolved"
priority: 10
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "low"
reversible: true
github_issue: 105
---

# 🔴 [Severity: HIGH]: Global/ThreadLocal ErrnoSegment Optimization

**Context:**
The current `nativeScope` utility creates a new `Arena.ofConfined()` for every system call wrapper in `LinuxNative.kt`. This is done primarily to allocate a 4-byte `ErrnoSegment` to capture the FFM downcall result. In high-frequency daemon reactor loops, this creates massive native allocator and GC overhead due to thousands of arenas being created per second. A previous attempt to optimize this by using a reentrant `ThreadLocal<Arena>` for `nativeScope` caused massive memory leaks because it reused the long-lived session arena for all transient allocations.

**Needed:**
Replace the `nativeScope` allocation of `ErrnoSegment` with a `ThreadLocal<MemorySegment>` backed by `Arena.global()` or `Arena.ofAuto()`. Since capturing `errno` only requires a fixed 4 bytes per thread, this persistent allocation is safe and will eliminate 95% of the implicit `nativeScope` allocation overhead across the entire project without affecting the session arena logic.

## Solution Options

### Option A — ThreadLocal backed by Arena.global()
Use `ThreadLocal.withInitial { Arena.global().allocate(4) }` for the `ErrnoSegment`.
**Pros:** Simplest implementation, zero GC overhead for the segment itself.
**Cons:** The 4-byte native allocation is never freed even if the thread dies (persistent memory).
**Risk:** LOW
**Effort:** small
**Files changed:** `LinuxNative.kt`, `MemoryWrappers.kt`

### Option B — ThreadLocal backed by Arena.ofAuto()
Use `ThreadLocal.withInitial { Arena.ofAuto().allocate(4) }` for the `ErrnoSegment`.
**Pros:** The native memory will be freed by the GC when the `ThreadLocal` value is unreachable (e.g. thread dies).
**Cons:** Slight GC involvement for tracking the `MemorySegment` lifecycle.
**Risk:** LOW
**Effort:** small
**Files changed:** `LinuxNative.kt`, `MemoryWrappers.kt`

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] `./gradlew :enforcer:test` passes.
- [ ] `./scripts/run_tests.sh` passes (container integration tests).
- [ ] Verify using a profiler or logs that `Arena.ofConfined()` allocations drop significantly during daemon reactor polling.
- [ ] Ensure `errno` capturing remains thread-safe and accurate in concurrent syscall scenarios.

**Implementation Hints:**
- Ensure the `ErrnoSegment` accessor retrieves the correct thread-local segment cleanly.
- Remove `nativeScope` wrapping from raw syscalls in `LinuxNative.kt` that no longer need it.
