---
title: "Uncaught Native Exceptions Escaping BPF Installation"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "seccomp"
effort: "small"
github_issue: 73
---

# 🔴 [Severity: MEDIUM]: Uncaught Native Exceptions Escaping BPF Installation

*   **Dimension:** Cascading Failure Analysis
*   **Target Area:** `io.mazewall.seccomp.PureJavaBpfEngine`
*   **Failure Hypothesis:** If `process_vm_readv` or `seccomp` downcalls throw an unhandled JVM Error or Exception (e.g., `OutOfMemoryError` during Arena allocation, or a sudden FFM `IllegalArgumentException`), the `installInternal` method catches `Throwable` and blindly sets the thread state to `Failed(stepName, errno, e)`, but it might leave the process in a partially restricted state where `no_new_privs` is enabled but the filter is missing.
*   **Context & Proof:** `PureJavaBpfEngine.installInternal` calls `setNoNewPrivs()`, builds the filter, and installs it. If an exception occurs after `setNoNewPrivs()` but before `installFilter`, the process has permanently locked its privileges (cannot call `execve` with setuid) without actually applying the security policy. Subsequent attempts to retry or recover might fail.
*   **Cascading Risk Potential:** Medium application stability defect. Leaves the JVM in a non-deterministic state where native OS state does not match the intended policy, potentially causing confusing failures during later application phases.
*   **Recommendation:** Document the permanence of `setNoNewPrivs` and ensure `installInternal` allocates memory and parses the policy *before* invoking `prctl(PR_SET_NO_NEW_PRIVS)` to minimize the critical section where partial failure can occur.
