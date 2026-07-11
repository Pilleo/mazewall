---
title: "Unhandled Signal Mask Inheritance in `ContainedExecutors`"
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

# 🔴 [Severity: MEDIUM]: Unhandled Signal Mask Inheritance in `ContainedExecutors`

**Context:**
**Hypothesis:** Standard JVM thread pools do not reset POSIX signal masks (`sigprocmask`) or alternate signal stacks (`sigaltstack`) when reusing threads. If a previous uncontained task executing native code (JNI/FFM) blocked `SIGSYS` or corrupted the signal stack, a subsequently contained task on that same carrier thread will not receive `SIGSYS` when it violates the seccomp policy, defeating `ACT_TRAP` actions.

`ContainedExecutors.wrap` applies the seccomp filter but relies on the kernel delivering `SIGSYS` if the user configures `ACT_TRAP`. If the thread's signal mask currently blocks `SIGSYS` (which is highly unusual for pure Java, but possible if native libraries are used), the kernel might leave the thread in an unkillable zombie state or delay the signal indefinitely.


**Needed:**
1. Document that `ACT_TRAP` is unreliable in environments where native libraries might modify thread signal masks.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``io.mazewall.enforcer.ContainedExecutors``
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
