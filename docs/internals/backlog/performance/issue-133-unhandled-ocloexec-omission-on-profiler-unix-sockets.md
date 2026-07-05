---
title: "Unhandled `O_CLOEXEC` Omission on Profiler Unix Sockets"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "profiler"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: Unhandled `O_CLOEXEC` Omission on Profiler Unix Sockets

*   **Dimension:** Cascading Failure Analysis (The Systems View)
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSocket.kt` (or similar)
*   **Failure Hypothesis:** If the profiler daemon opens UNIX sockets or files without `O_CLOEXEC`, these file descriptors could be inherited by child processes spawned by the profiled application.
*   **Context & Proof:** This could cause the profiler socket to remain open even if the daemon shuts down, or allow a malicious child process to interfere with the profiling session.
*   **Cascading Risk Potential:** Medium. File descriptor leaks and potential IPC interception.
*   **Recommendation:** Ensure all internal profiler sockets and file descriptors are explicitly opened with `O_CLOEXEC`.
