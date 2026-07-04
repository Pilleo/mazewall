---
title: "Unhandled Signal Interruptions (`EINTR`) in socket IO"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Unhandled Signal Interruptions (`EINTR`) in socket IO

*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt`
*   **Hypothesis:** If `socketFd.close()` is interrupted, will it cause resource leak?
*   **Context & Proof:** Trace listener uses standard sockets. They can throw exceptions.
*   **Recommendation:** Verify that close routines handle interruptions properly.
