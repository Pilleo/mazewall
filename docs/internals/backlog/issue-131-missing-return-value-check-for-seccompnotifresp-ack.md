---
title: "Missing Return Value Check for `SECCOMP_NOTIF_RESP` ACK"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Missing Return Value Check for `SECCOMP_NOTIF_RESP` ACK

*   **Dimension:** OS Invariants / Cascading Failure
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/strace/StraceProfiler.kt`
*   **Failure Hypothesis:** After processing a notification, the profiler sends a response back to the kernel via `ioctl(SECCOMP_IOCTL_NOTIF_SEND)`. If this `ioctl` fails (e.g., because the target thread was killed or interrupted in the meantime), the profiler might not handle the error gracefully, potentially leaking state or crashing the daemon loop.
*   **Context & Proof:** The `USER_NOTIF` documentation states that the target thread can be interrupted or killed before the response is sent. The `ioctl` will return `ENOENT` in this case.
*   **Cascading Risk Potential:** Medium. A crashed profiler daemon prevents further profiling of the application.
*   **Recommendation:** Explicitly catch and ignore `ENOENT` errors during the `NOTIF_SEND` `ioctl`, as they represent expected, normal race conditions during thread termination.
