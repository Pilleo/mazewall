---
title: "Race in Asynchronous / Fire-and-Forget Profiler Event Delivery"
severity: "CRITICAL"
status: "open"
priority: 5
dependencies: []
component: "profiler"
effort: "small"
autonomy: "supervised"
solution_approved: false
---


# 🔴 [Severity: CRITICAL]: Race in Asynchronous / Fire-and-Forget Profiler Event Delivery

*   **Dimension:** Micro-Implementation & State Machine Invariants
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt`, `io.mazewall.profiler.internal.ProfilerTraceListener.kt`
*   **Failure Hypothesis:** Removing the synchronous handshake protocol (`WAIT_FOR_ACK`) in the profiler to send events in a "fire-and-forget" manner allows the tracee thread to return from kernel space and resume execution *before* the JVM listener thread has finished reading the trace event and calling `Thread.getStackTrace()`. This results in either empty stack profiles (because the thread is no longer running in the expected call path) or race conditions where events are lost or associated with wrong call frames.
*   **Context & Proof:** During refactoring, the removal of the `WAIT_FOR_ACK` loop caused integration tests verifying stack trace capture to fail consistently, as `bob.stackProfile` became empty.
*   **Recommendation:** Strictly enforce the synchronous `WAIT_FOR_ACK` protocol inside the daemon's session loop (`ProfilerSessionHandler.processNotification`) and release the tracee thread only after the listener thread has written the `PROTOCOL_ACK_BYTE` back to the socket. Wrap the listener's ACK sending code in a `finally` block to prevent tracee starvation.

## Solution Options

### Option A
(To be filled)

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ]

**Implementation Hints:**
-
