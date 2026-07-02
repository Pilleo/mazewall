---
title: "Overly Broad Catch Block in `ProfilerDaemon.reactorLoop`"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: LOW]: Overly Broad Catch Block in `ProfilerDaemon.reactorLoop`

*   **Dimension:** Code Maintainability & Engineering Standards
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerDaemon.kt`
*   **Failure Hypothesis:** The main event loop of the profiler daemon might have a catch-all block that suppresses critical interrupts or unexpected structural failures.
*   **Context & Proof:** If `reactorLoop` catches `Exception` and just logs it, it might inadvertently catch `InterruptedException` without restoring the interrupt status, causing the daemon to hang during shutdown requests.
*   **Cascading Risk Potential:** Low. The daemon might need to be forcefully killed (`SIGKILL`) instead of shutting down cleanly.
*   **Recommendation:** Explicitly handle `InterruptedException` (by restoring the interrupt status and exiting the loop) and `ClosedByInterruptException` separately from general IO errors.
