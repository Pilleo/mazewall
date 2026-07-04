---
title: "Unhandled Signal Interruptions (`EINTR`) during `seccomp` Filter Installation"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) during `seccomp` Filter Installation

*   **Dimension:** OS Invariants / Cascading Failure
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt`
*   **Failure Hypothesis:** The `seccomp` syscall itself might return `EINTR` if a signal is delivered to the thread precisely during the kernel's filter installation phase.
*   **Context & Proof:** `LinuxNative.syscall` is used to invoke `seccomp`. While filter installation is generally fast, if `EINTR` occurs, the installation fails. The current code does not retry the `seccomp` syscall on `EINTR`.
*   **Cascading Risk Potential:** Medium. In a highly active system with many signals (e.g., a JVM handling many async IO events or timers), `ContainedExecutors.wrap` might randomly fail with `IllegalStateException` due to `EINTR`.
*   **Recommendation:** Wrap the `seccomp` syscall (and `prctl` fallback) in an explicit `while (errno == EINTR)` retry loop, as is standard practice for robust Linux system programming.
