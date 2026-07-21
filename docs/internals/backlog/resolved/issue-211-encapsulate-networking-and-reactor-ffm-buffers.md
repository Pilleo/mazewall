---
title: "Encapsulate Networking Streams and Daemon Reactor Buffers in io.mazewall.ffi"
severity: "HIGH"
status: "resolved"
priority: 10
dependencies: ["issue-209", "issue-210"]
component: "supervisor"
effort: "medium"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
github_issue: 139
---

# 🔴 [Severity: HIGH]: Encapsulate Networking Streams and Daemon Reactor Buffers in io.mazewall.ffi

**Context:**
Socket input streams (`SupervisorSocketInputStream` and `NativeSocketInputStream`) and daemon reactor loops (`SupervisorDaemonEngine`, `ProfilerDaemonEngine`) perform raw `MemorySegment` allocations, slicing, and buffer copies directly inside `io.mazewall.enforcer.supervisor` and `io.mazewall.profiler.internal`. This exposes raw FFM handling inside business logic and event loops.

**Needed:**
1. Refactor `SupervisorSocketInputStream` and `NativeSocketInputStream` to use `ManagedSegment` copy methods instead of direct `MemorySegment.copy` calls.
2. Refactor `SupervisorDaemonEngine` and `ProfilerDaemonEngine` to pass explicit `NativeArena` instances to session handler methods (`handleActiveListener`, `processNotification`).
3. Ensure reactor loops distinguish between persistent session-level allocations and transient iteration-level allocations (`PerTaskArena`).

## Solution Options

### Option A — Explicit NativeArena Scoping
Wrap iteration bodies in `NativeArena.ofConfined().use { iterationArena -> ... }` and pass `iterationArena` down to `SupervisorSessionHandler` methods.
**Pros:** Clean scoping, deterministic cleanup at the end of each iteration block.
**Cons:** Requires updating function signatures in session handlers.
**Risk:** MEDIUM
**Effort:** medium

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] `SupervisorSocketInputStream` and `NativeSocketInputStream` contain no `java.lang.foreign.*` imports.
- [ ] Daemon reactor loops clean up transient iteration memory deterministically.
- [ ] All unit and integration tests in `:enforcer:test` and `:profiler:test` pass.
