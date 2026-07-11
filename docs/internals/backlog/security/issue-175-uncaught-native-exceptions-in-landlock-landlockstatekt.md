---
title: "Uncaught Native Exceptions in Landlock `LandlockState.kt`"
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

# 🔴 [Severity: MEDIUM]: Uncaught Native Exceptions in Landlock `LandlockState.kt`

**Context:**
**Hypothesis:** If allocating rulesets fails, does it leak FDs?

`Landlock` uses FDs. If it crashes mid-setup, FD must be closed.


**Needed:**
1. Verify `use` is thoroughly applied or manual close happens on error paths.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/landlock/LandlockState.kt``
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
