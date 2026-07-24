---
title: "Missing Thread-Safety in `ProcessStateRegistry` Updates"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: MEDIUM]: Missing Thread-Safety in `ProcessStateRegistry` Updates

**Context:**
**Hypothesis:** Concurrent calls to `ContainedExecutors.installOnProcess` or `updateProcessState` might lead to lost updates or race conditions when merging policies if `ProcessStateRegistry.update` is not atomic.

In `ContainedExecutors.kt`, `updateProcessState` calls `ProcessStateRegistry.update { current -> current.withNewSeccompPolicy(...) }`. If `ProcessStateRegistry` uses a simple volatile variable without compare-and-swap (CAS) or synchronization, concurrent process-wide installations could overwrite each other's state, leading to an inconsistent view of the global containment policy.


**Needed:**
1. Ensure `ProcessStateRegistry.update` uses a synchronized block or `AtomicReference.updateAndGet` to guarantee atomicity of state transitions.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/enforcer/ProcessStateRegistry.kt` and `ContainedExecutors.kt``
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
