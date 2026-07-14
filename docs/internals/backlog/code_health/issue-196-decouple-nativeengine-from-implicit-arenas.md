---
title: "Decouple NativeEngine from Implicit Arenas"
severity: "MEDIUM"
status: "open"
priority: 8
dependencies: ["issue-194", "issue-195"]
component: "ffi"
effort: "large"
autonomy: "supervised"
solution_approved: false
blast_radius: "high"
reversible: false
---

# 🟡 [Severity: MEDIUM]: Decouple NativeEngine from Implicit Arenas

**Context:**
The `NativeEngine` and its implementing class `LinuxNative` use `nativeScope` implicitly for virtually every system call wrapper. While some wrappers only need it for the `ErrnoSegment` (which can be optimized via ThreadLocal), others (like `fileSystem.open` or `networking.socketpair`) implicitly create scoped arenas for transient string allocations or struct out-parameters. This design hides memory allocation boundaries from the caller, forcing the implementation to constantly create tiny `Arena` objects under the hood, violating best practices for zero-allocation wrappers and making memory footprint opaque.

**Needed:**
Audit the `NativeEngine` interface and `LinuxNative` implementation. Refactor methods that require memory allocation for arguments (e.g., path strings, `sockaddr` structs) to either accept a `context(Arena)` receiver, a `SegmentAllocator`, or force the caller to pre-allocate and pass the required `MemorySegment` natively. This will allow callers (like the daemon reactor loops) to use an explicit iteration-level arena for these allocations, completely decoupling `NativeEngine` from FFM scope management.

## Solution Options

### Option A — Explicit MemorySegment Parameters
Change `NativeEngine` signatures to accept pre-allocated `MemorySegment`s exclusively (e.g., instead of taking a `String` for paths, require a `MemorySegment` containing the C-string).
**Pros:** Pushes all memory management to the caller, leaving `NativeEngine` completely pure and allocation-free.
**Cons:** High refactoring effort across all `NativeEngine` consumers; test suites will become more verbose.
**Risk:** HIGH (API Breaking Change)
**Effort:** large
**Files changed:** `NativeEngine.kt`, `LinuxNative.kt`, `MockNativeEngine.kt`, all usages.

### Option B — Implicit context(Arena) Requirement
Add `context(Arena)` to any `NativeEngine` method that needs to allocate transient memory.
**Pros:** Easier transition for consumers; memory is allocated in the provided arena cleanly.
**Cons:** Still hides *when* allocations happen; requires Kotlin context receiver enablement.
**Risk:** MEDIUM (API Breaking Change)
**Effort:** medium
**Files changed:** `NativeEngine.kt`, `LinuxNative.kt`, `MockNativeEngine.kt`, all usages.

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] `./gradlew check` passes across all modules.
- [ ] `NativeEngine` interface no longer hides dynamic allocations (except potentially `ErrnoSegment`).
- [ ] `LinuxNative.kt` does not use `nativeScope { ... }` or `Arena.ofConfined().use { ... }` internally for per-syscall tasks.
- [ ] Consumers (like `SupervisorSessionHandler`) explicitly manage their memory using their iteration-level arenas.

**Implementation Hints:**
- Since this is a massive breaking change, coordinate it carefully after issues 194 and 195 are merged.
- Update `MockNativeEngine` carefully to reflect the new memory signatures.
