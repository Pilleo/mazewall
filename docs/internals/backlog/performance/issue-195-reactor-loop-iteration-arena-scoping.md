---
title: "Reactor Loop Iteration Arena Scoping"
severity: "HIGH"
status: "open"
priority: 9
dependencies: ["issue-194"]
component: "daemon"
effort: "medium"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: Reactor Loop Iteration Arena Scoping

**Context:**
The `SupervisorDaemonEngine` and `ProfilerDaemonEngine` loops utilize long-running "reactor" loops to handle incoming seccomp notifications. These loops wrap their entirety in a single `Arena.ofConfined().use { ... }` block to manage session-level native memory (e.g., `pollFds`). However, any transient memory allocated inside the event-handling body (e.g., reading strings from tracee memory) either triggers new arena creation (if wrapped in `nativeScope`) or leaks linearly if a shared reentrant arena is used, causing eventual OOM or JVM hangs.

**Needed:**
The daemon reactor loops must be refactored to distinguish between session-level and iteration-level memory. A new, short-lived `Arena.ofConfined().use { ... }` block must be introduced *inside* the `while(!isGlobalShutdown())` loop body to serve as a `PerTaskArena`. This ensures that all transient memory allocated while processing a single seccomp notification is deterministically cleaned up at the end of the iteration, preventing linear growth without the high overhead of per-syscall arenas.

## Solution Options

### Option A — Explicit Nested Arena
Inside the `while` loop, wrap the body with `Arena.ofConfined().use { iterationArena -> ... }` and explicitly pass `iterationArena` down to the `SessionHandler`.
**Pros:** Clean scoping, deterministic cleanup at the end of the iteration block.
**Cons:** Requires refactoring the `SessionHandler` signatures to accept an explicit `iterationArena`.
**Risk:** MEDIUM
**Effort:** medium
**Files changed:** `SupervisorDaemonEngine.kt`, `ProfilerDaemonEngine.kt`, `SupervisorSessionHandler.kt`, `ProfilerSessionHandler.kt`

### Option B — Context Receiver Arena
Use a context receiver `context(Arena)` for the `SessionHandler` methods, providing the iteration arena implicitly to the scope.
**Pros:** Cleaner syntax, avoids polluting function arguments.
**Cons:** Requires Kotlin context receiver flags enabled; can make it obscure which arena is being used if nested.
**Risk:** MEDIUM
**Effort:** medium
**Files changed:** `SupervisorDaemonEngine.kt`, `ProfilerDaemonEngine.kt`, `SupervisorSessionHandler.kt`, `ProfilerSessionHandler.kt`

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] `./gradlew :enforcer:test` and `./gradlew :profiler:test` pass.
- [ ] `./scripts/run_tests.sh` passes successfully without JVM hangs.
- [ ] The linear memory leak in daemon loops during high-volume testing is eliminated.
- [ ] Session-level allocations (like `pollFds`) remain persistent across iterations.

**Implementation Hints:**
- Ensure you don't inadvertently reallocate session-level memory inside the inner iteration loop.
- Any memory that needs to persist across loop iterations must be allocated using the outer session arena.
