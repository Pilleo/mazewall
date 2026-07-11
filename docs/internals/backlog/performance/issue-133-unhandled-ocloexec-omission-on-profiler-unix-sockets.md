---
title: "Unhandled `O_CLOEXEC` Omission on Profiler Unix Sockets"
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

# 🔴 [Severity: MEDIUM]: Unhandled `O_CLOEXEC` Omission on Profiler Unix Sockets

**Context:**
**Hypothesis:** If the profiler daemon opens UNIX sockets or files without `O_CLOEXEC`, these file descriptors could be inherited by child processes spawned by the profiled application.

This could cause the profiler socket to remain open even if the daemon shuts down, or allow a malicious child process to interfere with the profiling session.


**Needed:**
1. Ensure all internal profiler sockets and file descriptors are explicitly opened with `O_CLOEXEC`.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSocket.kt` (or similar)`
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
