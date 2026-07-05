---
title: "Asynchronous Supervisor socket reads timeout failure handling"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: Asynchronous Supervisor socket reads timeout failure handling

*   **Dimension:** Verification via Types & Compiler Features
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt`
*   **Failure Hypothesis:** The `readAndHandleJvmResponse` waits up to 1 second (`POLL_TIMEOUT_MS`) for the JVM to validate the stack trace.
*   **Context & Proof:** If the JVM `JvmStackInspector.inspect` or `scopingPolicy.authorize` takes more than `POLL_TIMEOUT_MS` (e.g., due to garbage collection pauses or complex policy evaluation), `poll` times out. The `SupervisorSessionHandler` logs a severe error and sends an `EPERM` error via `sendSeccompError` to the tracee. Later, when the JVM finally finishes and sends the response, the supervisor receives an unexpected response or ignores it, breaking the synchronization protocol.
*   **Recommendation:** Remove the arbitrary timeout for the JVM validation step. The daemon should wait indefinitely (or loop on poll) for the JVM to respond. If the JVM hangs, the tracee should hang as well. Introducing timeouts at the IPC boundary leads to desynchronization and potential subsequent failure in tracking future system calls.
