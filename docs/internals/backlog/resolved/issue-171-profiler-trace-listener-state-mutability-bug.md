---
title: "Profiler Trace Listener State Mutability Bug"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Profiler Trace Listener State Mutability Bug

*   **Status:** RESOLVED (June 2026)
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt`
*   **Context & Proof:** The `ProfilerTraceListener` thread might leak resources or deadlock if an unhandled exception crashes the listener loop before `closed.set(true)` or `socketFd` is released.
*   **Fix:** Wrapped the listener thread's run execution in a `try-catch` catching `Throwable`. On fatal crash, it logs the error, marks the listener as closed, and closes the socket FD to ensure deterministic resource cleanup.
