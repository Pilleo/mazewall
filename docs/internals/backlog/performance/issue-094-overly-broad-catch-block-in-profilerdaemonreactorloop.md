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
**Hypothesis:** The `reactorLoop` wraps the entire multiplexing process in a generic `try { ... } catch (e: Exception) { logger.log(Level.SEVERE, "Daemon loop error", e) }` block. If an unrecoverable FFM error (like `IllegalArgumentException` from a bad layout cast) or an `OutOfMemoryError` occurs, the loop swallows it, logs it, and continues executing. This can lead to a spinning loop of failures, 100% CPU utilization, and corrupted profiler state.

Generic exception catching inside infinite daemon loops often hides critical system state corruption. If the daemon encounters a corrupted `USER_NOTIF` packet structure, it will crash processing that packet, catch the error, and immediately poll again, likely receiving the exact same corrupted packet or losing synchronization with the kernel queue.


**Needed:**
1. Differentiate between recoverable I/O exceptions (like `IOException` on a dropped connection) and unrecoverable structural errors (like `IllegalArgumentException` or `IndexOutOfBoundsException`). The daemon should intentionally crash or disconnect the specific session on structural errors to prevent infinite error spinning.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``io.mazewall.profiler.engine.ProfilerDaemon``
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
