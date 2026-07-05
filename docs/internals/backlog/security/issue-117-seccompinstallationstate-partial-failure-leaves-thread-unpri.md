---
title: "`SeccompInstallationState` Partial Failure Leaves Thread Unprivileged but Uncontained"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: HIGH]: `SeccompInstallationState` Partial Failure Leaves Thread Unprivileged but Uncontained

*   **Dimension:** Cascading Failure Analysis (The Systems View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt` and `SeccompInstallationState.kt`
*   **Failure Hypothesis:** If an exception (like OutOfMemoryError, or a virtual machine error) occurs after `setNoNewPrivs` but before the BPF filter is successfully applied, the OS thread will have `no_new_privs` permanently set, but no containment filter will be active.
*   **Context & Proof:** In `PureJavaBpfEngine.installInternal`, `val locked = uninitialized.lockPrivileges()` sets `PR_SET_NO_NEW_PRIVS`. Then, `nativeScope { val built = locked.buildFilter(this, policy) }` attempts to allocate native memory for the BPF instructions. If this native allocation fails (e.g., due to memory pressure or FFM limits), an exception is thrown. The method catches the exception and updates the state to `Failed`. However, `PR_SET_NO_NEW_PRIVS` cannot be unset. The thread is now returned to the pool (if running via `ContainedExecutors.wrap`), silently dropping privileges for all future tasks on this carrier thread without actually applying the requested security policy.
*   **Cascading Risk Potential:** High. Thread pool contamination. Future tasks executing on this thread will fail unexpectedly if they legitimately require privilege escalation (e.g., `execve` with setuid), leading to non-deterministic failures across the application.
*   **Recommendation:** Pre-allocate the `MemorySegment` and build the `SockFProg` struct *before* calling `setNoNewPrivs()`. This ensures that all memory and layout calculations succeed before making the irreversible kernel state change.
