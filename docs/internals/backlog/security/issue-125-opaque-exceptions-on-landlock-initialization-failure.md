---
title: "🔴 [Severity: DX-FRICTION]: Opaque Exceptions on Landlock Initialization Failure"
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

# 🔴 [Severity: DX-FRICTION]: Opaque Exceptions on Landlock Initialization Failure

**Context:**
**Hypothesis:** If Landlock fails to initialize due to a missing kernel capability or an older ABI version, the exception message might be opaque (e.g., just returning an `errno`), confusing developers about whether the system is supported.

If `landlock_create_ruleset` returns `ENOSYS` or `EOPNOTSUPP`, a generic `IllegalStateException` or `RuntimeException` without context about Kernel requirements hurts the DX.


**Needed:**
1. Wrap Landlock native call failures in a specific `UnsupportedKernelFeatureException` with clear guidance on required Linux kernel versions.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt``
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
