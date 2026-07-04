---
title: "Unhandled Signal Interruptions (`EINTR`) during Supervisor Initialization"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) during Supervisor Initialization

*   **Dimension:** Micro-Implementation & State Machine Invariants
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSeccompNotifInstaller.kt`
*   **Failure Hypothesis:** Sending the socket file descriptors over `SCM_RIGHTS` can be interrupted by signals.
*   **Context & Proof:** `SupervisorSocketUtils.sendDescriptor` relies on `sendmsg`, which can fail with `EINTR`. If `EINTR` occurs while `SupervisorSeccompNotifInstaller` is trying to pass the seccomp listener FD to the supervisor daemon, the daemon will not receive the FD, but the JVM might proceed or throw an error, leading to an inconsistent state or unmonitored tracee.
*   **Recommendation:** Wrap `sendmsg` inside `SupervisorSocketUtils.sendDescriptor` in a `while (errno == EINTR)` retry loop.
