---
title: "Unhandled Signal Interruptions (`EINTR`) in socket IO"
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

# 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) in socket IO

**Context:**
**Hypothesis:** If `socketFd.close()` is interrupted, will it cause resource leak?

Trace listener uses standard sockets. They can throw exceptions.


**Needed:**
1. Verify that close routines handle interruptions properly.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt``
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
