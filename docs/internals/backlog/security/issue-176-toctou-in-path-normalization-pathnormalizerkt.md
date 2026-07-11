---
title: "TOCTOU in Path Normalization `PathNormalizer.kt`"
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

# 🔴 [Severity: MEDIUM]: TOCTOU in Path Normalization `PathNormalizer.kt`

**Context:**
**Hypothesis:** Can an attacker rename directory to bypass path normalizer?

`PathNormalizer` does static analysis. Does the system ensure paths aren't modified post-normalization?


**Needed:**
1. Verify path resolution constraints are verified against Landlock or Seccomp hooks safely.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/sbob/PathNormalizer.kt``
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
