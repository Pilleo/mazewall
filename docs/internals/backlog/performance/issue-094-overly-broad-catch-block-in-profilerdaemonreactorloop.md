---
title: "Overly Broad Catch Block in `ProfilerDaemon.reactorLoop`"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: LOW]: Overly Broad Catch Block in `ProfilerDaemon.reactorLoop`

*   **Dimension:** Cascading Failure Analysis
*   **Target Area:** `io.mazewall.profiler.engine.ProfilerDaemon`
*   **Failure Hypothesis:** The `reactorLoop` wraps the entire multiplexing process in a generic `try { ... } catch (e: Exception) { logger.log(Level.SEVERE, "Daemon loop error", e) }` block. If an unrecoverable FFM error (like `IllegalArgumentException` from a bad layout cast) or an `OutOfMemoryError` occurs, the loop swallows it, logs it, and continues executing. This can lead to a spinning loop of failures, 100% CPU utilization, and corrupted profiler state.
*   **Context & Proof:** Generic exception catching inside infinite daemon loops often hides critical system state corruption. If the daemon encounters a corrupted `USER_NOTIF` packet structure, it will crash processing that packet, catch the error, and immediately poll again, likely receiving the exact same corrupted packet or losing synchronization with the kernel queue.
*   **Cascading Risk Potential:** Low security risk but high stability risk for the profiler daemon itself.
*   **Recommendation:** Differentiate between recoverable I/O exceptions (like `IOException` on a dropped connection) and unrecoverable structural errors (like `IllegalArgumentException` or `IndexOutOfBoundsException`). The daemon should intentionally crash or disconnect the specific session on structural errors to prevent infinite error spinning.
