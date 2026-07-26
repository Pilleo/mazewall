---
title: "Untrusted Allocation Size Causes `OutOfMemoryError` and Daemon Crash via `connect()`"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: Untrusted Allocation Size Causes `OutOfMemoryError` and Daemon Crash via `connect()`

*   **Status:** RESOLVED (June 2026)
*   **Dimension:** Vulnerability Chaining & Concurrency (The Sandbox View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/ffi/memory/SupervisorProcessMemoryReader.kt` and `SupervisorSessionHandler.kt`
*   **Context & Proof:** A tracee can intentionally crash the supervisor daemon by passing an extremely large `addrlen` argument to the `connect` syscall, triggering a fatal `OutOfMemoryError` that is not caught by standard exception handlers.
*   **Fix:** Wrapped the body of `processNotification` in `SupervisorSessionHandler` in a global `try-catch` catching `Throwable`. Any fatal error or OOM now fails-closed safely, logging the error and returning `EPERM` without crashing the daemon thread.
