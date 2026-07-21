---
title: "Silent Fallback by default behavior evaluation"
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
github_issue: 151
---

# 🔴 [Severity: LOW]: Silent Fallback by default behavior evaluation

**Context:**
**Hypothesis:** If Landlock or Seccomp is missing, does the system securely fail, or does it bypass containment silently by default?

`Platform.configuredFallback()` checks `io.mazewall.fallback` properties and defaults to `Platform.FallbackBehavior.FAIL` if not set. `ContainedExecutors.kt` and `Landlock.kt` correctly call this method and throw an `UnsupportedOperationException` if `FAIL` is the configured behavior.


**Needed:**
1. The fallback behavior is secure by default (fail-closed) and correctly implemented.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/Platform.kt` and usages of `Platform.configuredFallback()``
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
