---
title: "Uncaught Native Exceptions Escaping Landlock Installation"
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

# 🔴 [Severity: MEDIUM]: Uncaught Native Exceptions Escaping Landlock Installation

**Context:**
**Hypothesis:** `LandlockSession.applyRuleset` handles FFM resources but may not correctly propagate or contain exceptions during intermediate installation phases.

In `LandlockSession.applyRuleset`, `nativeScope` and `try-catch` are used. However, if `Landlock.createRuleset` throws an unexpected runtime exception (e.g., an FFM `IllegalStateException` due to memory alignment issues on a weird kernel, rather than a managed `SyscallResult.Error`), the exception bypasses the standard `state = LandlockState.Failed(err)` setting because it's outside the inner `try` block that wraps `added.restrictSelf(processWide)`.


**Needed:**
1. Wrap the entire logic from `state = LandlockState.CreatingRuleset(abi)` onwards inside a comprehensive `try-catch` block that correctly transitions the state to `LandlockState.Failed(e)` for any `Throwable`, ensuring the failure state is strictly recorded before throwing.

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
