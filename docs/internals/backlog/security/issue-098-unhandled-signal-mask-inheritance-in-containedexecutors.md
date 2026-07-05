---
title: "Unhandled Signal Mask Inheritance in `ContainedExecutors`"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: Unhandled Signal Mask Inheritance in `ContainedExecutors`

*   **Dimension:** OS Invariants
*   **Target Area:** `io.mazewall.enforcer.ContainedExecutors`
*   **Failure Hypothesis:** Standard JVM thread pools do not reset POSIX signal masks (`sigprocmask`) or alternate signal stacks (`sigaltstack`) when reusing threads. If a previous uncontained task executing native code (JNI/FFM) blocked `SIGSYS` or corrupted the signal stack, a subsequently contained task on that same carrier thread will not receive `SIGSYS` when it violates the seccomp policy, defeating `ACT_TRAP` actions.
*   **Context & Proof:** `ContainedExecutors.wrap` applies the seccomp filter but relies on the kernel delivering `SIGSYS` if the user configures `ACT_TRAP`. If the thread's signal mask currently blocks `SIGSYS` (which is highly unusual for pure Java, but possible if native libraries are used), the kernel might leave the thread in an unkillable zombie state or delay the signal indefinitely.
*   **Cascading Risk Potential:** Medium. `mazewall` currently defaults to `ACT_ERRNO`, avoiding `SIGSYS` handling entirely. But if developers use `ACT_TRAP` for debugging or specific integrations, signal masking will break containment reporting.
*   **Recommendation:** Document that `ACT_TRAP` is unreliable in environments where native libraries might modify thread signal masks.
