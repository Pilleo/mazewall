---
title: "Inefficient ThreadLocal usage in `ThreadStateRegistry`"
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
github_issue: 199
---

# 🔴 [Severity: PERFORMANCE]: Inefficient ThreadLocal usage in `ThreadStateRegistry`

**Context:**
**Hypothesis:** Frequent access to `ThreadStateRegistry.state` during high-throughput executor task wrapping adds measurable overhead due to `ThreadLocal` lookups.

`ContainedExecutors.resolveCurrentState()` accesses both `ThreadStateRegistry.state` and `ProcessStateRegistry.state`. In a tight loop (e.g., thousands of small tasks submitted to a wrapped executor), the continuous merging of Thread and Process states dominates the overhead.


**Needed:**
1. Cache the merged `ContainerState` or optimize the `ThreadLocal` access patterns. Consider using Loom's `ScopedValue` when available.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/enforcer/ThreadStateRegistry.kt``
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
