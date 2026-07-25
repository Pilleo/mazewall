---
title: "Race in Asynchronous / Fire-and-Forget Profiler Event Delivery"
severity: "CRITICAL"
status: "resolved"
priority: 5
dependencies: []
component: "profiler"
effort: "small"
---

# 🔴 [Severity: CRITICAL]: Race in Asynchronous / Fire-and-Forget Profiler Event Delivery

*   **Dimension:** Micro-Implementation & State Machine Invariants
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt`, `io.mazewall.profiler.internal.ProfilerTraceListener.kt`
*   **Failure Hypothesis:** Removing the synchronous handshake protocol (`WAIT_FOR_ACK`) in the profiler to send events in a "fire-and-forget" manner allows the tracee thread to return from kernel space and resume execution *before* the JVM listener thread has finished reading the trace event and calling `Thread.getStackTrace()`. This results in either empty stack profiles (because the thread is no longer running in the expected call path) or race conditions where events are lost or associated with wrong call frames.
*   **Context & Proof:** During refactoring, the removal of the `WAIT_FOR_ACK` loop caused integration tests verifying stack trace capture to fail consistently, as `bob.stackProfile` became empty.
*   **Recommendation:** Strictly enforce the synchronous `WAIT_FOR_ACK` protocol inside the daemon's session loop (`ProfilerSessionHandler.processNotification`) and release the tracee thread only after the listener thread has written the `PROTOCOL_ACK_BYTE` back to the socket. Wrap the listener's ACK sending code in a `finally` block to prevent tracee starvation.

## Solution / Verification
- **Handshake Verification:** Confirmed that both `ProfilerSessionHandler.kt` and `ProfilerTraceListener.kt` strictly and correctly enforce the synchronous `WAIT_FOR_ACK` protocol.
  - Specifically, `ProfilerSessionHandler` calls `handshake.performHandshake` and awaits the ACK before replying with a seccomp `CONTINUE` response to unblock the tracee.
  - On the JVM side, `ProfilerTraceListener` captures the tracee thread's stack trace *before* returning and sending `sendAck()` within a `finally` block, completely avoiding empty or raced stack traces and preventing tracee thread starvation.
- **Verification:** Ran `clean test` and `:profiler:integrationTest` to ensure that all profiling, event logging, and stack profile tests execute without errors. All tests pass successfully with 100% correctness.
