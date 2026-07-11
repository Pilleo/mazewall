---
title: "Process-Wide Classloader Deadlock on Profiler Result / State Types"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "profiler"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: Process-Wide Classloader Deadlock on Profiler Result / State Types

**Context:**
**Hypothesis:** Under process-wide profiling (`processWide = true`), all JVM threads are intercepted by seccomp `USER_NOTIF`. If classes required by the JVM listener thread (e.g., `ProfilingResult`, `TraceListenerState` subclasses) are loaded lazily, the listener thread will trigger class loading. This class loading will attempt to acquire the JVM ClassLoader monitor. If a tracee thread currently holds that monitor and is blocked in kernel space waiting for the listener to process its event, the listener thread will block indefinitely waiting for the ClassLoader monitor. This causes a circular deadlock.

Integration tests for process-wide profiling threw `NoClassDefFoundError` or hung indefinitely when classes like `ProfilingResult` or `TraceListenerState$ReadingHeader` were accessed during profiling but not warmed up beforehand.


**Needed:**
1. Maintain a robust, static class loading warmup block in `Profiler.kt` that instantiates and calls methods on all core state/result classes (`ProfilingResult`, `BobCompiler`, `TraceListenerState` subclasses) before installing seccomp filters.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt``
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
