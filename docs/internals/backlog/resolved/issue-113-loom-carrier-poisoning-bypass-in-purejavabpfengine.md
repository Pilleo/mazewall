---
title: "Loom Carrier Poisoning Bypass in `PureJavaBpfEngine`"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "enforcer"
effort: "medium"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: MEDIUM]: Loom Carrier Poisoning Bypass in `PureJavaBpfEngine`

**Context:**
*   **Dimension:** OS Invariants & Native Safety
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt`
*   **Observation:** The mandatory assertion `check(!Thread.currentThread().isVirtual)` is present in `ContainedExecutors` but absent in the public `PureJavaBpfEngine.install` methods. An advanced user could bypass the high-level API and poison carrier threads by calling the engine directly from a virtual thread.
*   **Needed:** Move the virtual thread check into `SeccompInstallationState.Uninitialized.lockPrivileges()` to ensure it is always enforced.

**Needed:**
1. Implement a fix based on the issue description.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt``
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
