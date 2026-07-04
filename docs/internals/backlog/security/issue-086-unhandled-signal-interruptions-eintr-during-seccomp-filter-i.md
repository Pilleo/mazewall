---
title: "Unhandled Signal Interruptions (`EINTR`) during `seccomp` Filter Installation"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) during `seccomp` Filter Installation

*   **Dimension:** OS Invariants
*   **Target Area:** `io.mazewall.seccomp.PureJavaBpfEngine`
*   **Failure Hypothesis:** If the `seccomp` downcall in `installFilter` is interrupted by an asynchronous POSIX signal (e.g., a JVM profiling signal or timer tick), it may fail with `EINTR`. The current code does not retry the syscall on `EINTR` and immediately throws an `IllegalStateException`, aborting the installation.
*   **Context & Proof:** The `PureJavaBpfEngine.installFilter` method calls `LinuxNative.syscall(NativeConstants.SECCOMP_SET_MODE_FILTER, ...)`. The kernel can interrupt almost any blocking or slow system call with `EINTR`. If `seccomp` returns `EINTR`, `r3.returnValue` will not be `0`, and the code falls back to `prctl`, which might also fail or behave unexpectedly. The method lacks a robust `while (errno == EINTR)` retry loop.
*   **Cascading Risk Potential:** Medium stability risk. Spurious `EINTR` signals could cause non-deterministic failures when initializing the sandbox in heavily multi-threaded or profiled JVM environments.
*   **Recommendation:** Wrap the `seccomp` and `prctl` filter installation downcalls in a retry loop that specifically handles `EINTR`.
