---
title: "Unhandled Signal Mask Inheritance in `ContainedExecutors`"
severity: "HIGH"
status: "open"
priority: 5
dependencies: []
component: "enforcer"
effort: "medium"
autonomy: "supervised"
solution_approved: false
---


# 🔴 [Severity: MEDIUM]: Unhandled Signal Mask Inheritance in `ContainedExecutors`

*   **Dimension:** OS Invariants / Cascading Failure
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt`
*   **Failure Hypothesis:** When a new thread is spawned by a wrapped `ExecutorService`, it inherits the signal mask of its parent. If the seccomp filter restricts `rt_sigprocmask`, the new thread might be permanently trapped with blocked signals.
*   **Context & Proof:** `ContainedExecutors.wrap` applies policies to threads dynamically. If a policy blocks `rt_sigprocmask` (or `ACT_ERRNO`), and the application relies on handling signals (e.g., `SIGTERM`), the thread will be unable to unblock them. This interacts poorly with Loom or certain async frameworks that manipulate signal masks for IO interruption.
*   **Cascading Risk Potential:** Medium. Could lead to unkillable threads or missed interruptions (e.g., `Thread.interrupt()` failing to wake up a blocked IO call if the underlying signal is blocked and cannot be manipulated).
*   **Recommendation:** Document that policies should ideally allow `rt_sigprocmask` and `rt_sigaction` for standard JVM thread management, and verify that `BpfFilter.getJvmCriticalNrs` explicitly includes them.

## Solution Options

### Option A
(To be filled)

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ]

**Implementation Hints:**
-
