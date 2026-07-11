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
**Hypothesis:** If installing a policy fails, does it clean up ThreadLocals?

Wrapping tasks needs robust try-finally for thread local registries.


**Needed:**
1. Verify that executor wrappers properly handle seccomp installation failures and clean state.

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
