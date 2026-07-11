---
title: "`PureJavaBpfEngine` Thread State Synchronization"
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

# 🔴 [Severity: MEDIUM]: `PureJavaBpfEngine` Thread State Synchronization

**Context:**
**Hypothesis:** The `PureJavaBpfEngine` uses a `ThreadLocal` called `threadState` to track the installation progress (e.g. `PrivilegesLocked`, `FilterBuilt`, `SystemCallApplied`). When `installOnProcess` is called, it installs a global seccomp filter using the `TSYNC` flag, affecting all sibling threads. However, it only updates the `ThreadLocal` state of the *calling* thread.

In `installInternal`, the code calls `threadState.set(SeccompInstallationState...)` sequentially. Since `threadState` is a `ThreadLocal`, sibling threads that were just subjected to the `TSYNC` seccomp filter will still evaluate `PureJavaBpfEngine.state` as `Uninitialized`. If any sibling thread later attempts to verify its installation state or perform operations that check `state`, it will falsely believe no filter is applied.


**Needed:**
1. Document this state divergence, or implement a global `processState` alongside `threadState` so that `installOnProcess` correctly signals global containment.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt` (specifically `installOnProcess` and `threadState`)`
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
