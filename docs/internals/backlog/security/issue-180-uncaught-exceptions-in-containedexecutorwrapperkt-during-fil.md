---
title: "Uncaught exceptions in `ContainedExecutorWrapper.kt` during filter installation"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: MEDIUM]: Uncaught exceptions in `ContainedExecutorWrapper.kt` during filter installation

**Context:**
**Hypothesis:** If `ContainedExecutors.installOnCurrentThread(policy, scopingPolicy)` fails midway (e.g., Landlock throws an exception before Seccomp is installed, or the supervisor connection fails), the `ThreadStateRegistry` might be left in a partially updated or corrupted state because `installOnCurrentThread` updates `ThreadStateRegistry.state` incrementally as it installs filters, but doesn't roll back on failure.

`ContainedExecutors.installInternal` calls `applyLandlockIfNecessary` which updates `ThreadStateRegistry.state`. If the subsequent `installSeccompFilter` fails, the thread state registry will reflect Landlock applied, but the seccomp filter might not be, or worse, the `SupervisorSession` might not be created properly. While `ContainedExecutorWrapper` uses `.use {}` to close the `AutoCloseable` return value of `installOnCurrentThread`, it doesn't clean up the `ThreadStateRegistry.state` if the *installation itself* throws an exception.


**Needed:**
1. `ContainedExecutors.installInternal` should probably take a snapshot of the current state, and use a `try-catch` to restore the original `ThreadStateRegistry.state` (and potentially Landlock/Seccomp state, though those are harder to revert) if the installation throws an error, or the Wrapper should handle it.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt``
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
