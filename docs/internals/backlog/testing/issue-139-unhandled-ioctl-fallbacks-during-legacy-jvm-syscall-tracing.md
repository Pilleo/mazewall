---
title: "Unhandled `IOCTL` fallbacks during legacy JVM syscall tracing"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "profiler"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: Unhandled `IOCTL` fallbacks during legacy JVM syscall tracing

*   **Dimension:** Macro-Architecture & OS Invariants
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemon.kt`
*   **Failure Hypothesis:** The profiler traces syscalls using `USER_NOTIF`. If a JVM performs an `ioctl` on a terminal or specialized device file, the BPF filter might intercept it. However, the profiler might not understand the specific `ioctl` structure to extract meaningful paths or context.
*   **Context & Proof:** `ioctl` arguments are highly dependent on the specific command. If the profiler attempts to read the argument as a string pointer (like it does for `open`), it might read random memory, causing a segmentation fault in the target process or reading garbage data.
*   **Cascading Risk Potential:** Medium. Could lead to garbage data in profiling logs or target process crashes if the profiler attempts to mutate the argument.
*   **Recommendation:** Ensure the BPF filter for the profiler either explicitly ignores `ioctl` (allowing it to pass through) or the `ProfilerDaemon` correctly identifies it as a generic, opaque operation without attempting deep pointer dereferencing.
