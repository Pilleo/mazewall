---
title: "Potential Race Condition in Async IO Thread Shutdown"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: true
blast_radius: "medium"
reversible: true
github_issue: 171
---

# 🔴 [Severity: MEDIUM]: Potential Race Condition in Async IO Thread Shutdown

**Context:**
**Hypothesis:** When a `ContainedExecutorWrapper` is shut down (`shutdown()`), it delegates to the underlying executor. If tasks are still in the queue, they will be executed. If the global `ProcessStateRegistry` is modified concurrently during this shutdown phase, the late-running tasks might pick up an inconsistent state.

The wrapper relies on dynamically checking `ThreadStateRegistry` and `ProcessStateRegistry`. If a task starts executing exactly as the application is tearing down or changing global policies, the state resolution might interleave unpredictably.


**Needed:**
1. Ensure `resolveCurrentState` is robust against concurrent modifications, or document that modifying global policies while executors are shutting down is unsupported.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt``
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** Option A (Resolved via PR #172)

**Acceptance Criteria:**
- [x] Tests verify the fix works as expected.
- [x] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
