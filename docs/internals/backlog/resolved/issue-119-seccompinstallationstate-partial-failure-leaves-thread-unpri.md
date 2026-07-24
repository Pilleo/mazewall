---
title: "`SeccompInstallationState` Partial Failure Leaves Thread Unprivileged but Uncontained"
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
github_issue: 205
---

# 🔴 [Severity: HIGH]: `SeccompInstallationState` Partial Failure Leaves Thread Unprivileged but Uncontained

**Context:**
**Hypothesis:** If an exception (like OutOfMemoryError, or a virtual machine error) occurs after `setNoNewPrivs` but before the BPF filter is successfully applied, the OS thread will have `no_new_privs` permanently set, but no containment filter will be active.

In `PureJavaBpfEngine.installInternal`, `val locked = uninitialized.lockPrivileges()` sets `PR_SET_NO_NEW_PRIVS`. Then, `nativeScope { val built = locked.buildFilter(this, policy) }` attempts to allocate native memory for the BPF instructions. If this native allocation fails (e.g., due to memory pressure or FFM limits), an exception is thrown. The method catches the exception and updates the state to `Failed`. However, `PR_SET_NO_NEW_PRIVS` cannot be unset. The thread is now returned to the pool (if running via `ContainedExecutors.wrap`), silently dropping privileges for all future tasks on this carrier thread without actually applying the requested security policy.


**Needed:**
1. Pre-allocate the `MemorySegment` and build the `SockFProg` struct *before* calling `setNoNewPrivs()`. This ensures that all memory and layout calculations succeed before making the irreversible kernel state change.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt` and `SeccompInstallationState.kt``
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
