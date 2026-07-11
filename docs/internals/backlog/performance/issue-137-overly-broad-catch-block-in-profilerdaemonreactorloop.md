---
title: "Overly Broad Catch Block in `ProfilerDaemon.reactorLoop`"
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

# 🔴 [Severity: LOW]: Overly Broad Catch Block in `ProfilerDaemon.reactorLoop`

**Context:**
**Hypothesis:** The main event loop of the profiler daemon might have a catch-all block that suppresses critical interrupts or unexpected structural failures.

If `reactorLoop` catches `Exception` and just logs it, it might inadvertently catch `InterruptedException` without restoring the interrupt status, causing the daemon to hang during shutdown requests.


**Needed:**
1. Explicitly handle `InterruptedException` (by restoring the interrupt status and exiting the loop) and `ClosedByInterruptException` separately from general IO errors.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemon.kt``
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
