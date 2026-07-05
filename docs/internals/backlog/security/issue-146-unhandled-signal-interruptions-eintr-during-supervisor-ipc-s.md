---
title: "Unhandled Signal Interruptions (`EINTR`) during Supervisor IPC socket communication"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) during Supervisor IPC socket communication

*   **Dimension:** Micro-Implementation & State Machine Invariants
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Failure Hypothesis:** The JVM validation loop reads from and writes to the supervisor socket. System calls over the socket can be interrupted by signals (`EINTR`).
*   **Context & Proof:** In `SupervisorSessionHandler.kt`, `LinuxNative.poll`, `LinuxNative.memory.write`, etc. are used. The `readAndHandleJvmResponse` and `sendSeccompError`/`sendSeccompContinue` functions do not properly loop on `EINTR` when calling `write` or `ioctl`. If a signal is received during the `ioctl(SECCOMP_IOCTL_NOTIF_SEND)` call, it may fail with `EINTR`, leaving the tracee suspended forever as the notification is never correctly answered.
*   **Recommendation:** Wrap the `ioctl` calls for `SECCOMP_IOCTL_NOTIF_SEND` and `SECCOMP_IOCTL_NOTIF_ADDFD` in an explicit retry loop that checks `if (errno == EINTR) continue`.
