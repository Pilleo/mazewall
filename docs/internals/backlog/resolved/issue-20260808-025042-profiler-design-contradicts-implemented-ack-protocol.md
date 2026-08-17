---
title: "Profiler Design Contradicts the Implemented Synchronous ACK Protocol"
severity: "HIGH"
status: resolved
priority: low
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "docs/internals/designs/profiler/profiler-design.md"
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilerTraceListener.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/ProfilerSessionHandler.kt"
effort: "large"
autonomy: "supervised"
---

# 🔴 [Severity: HIGH]: Profiler Design Contradicts the Implemented Synchronous ACK Protocol

**Context:** One section declares synchronous ACK a permanent invariant needed for stack attribution. A later section calls that architecture broken, documents fire-and-forget as the correct implementation, and says handshake methods were deleted. The current code still captures `Thread.getStackTrace()` before `sendAck()` and the daemon still waits for that ACK before sending `SECCOMP_USER_NOTIF_FLAG_CONTINUE`. The document therefore cannot be used to determine the live protocol or its safepoint/deadlock properties. Its claim that fire-and-forget loses no information is also too strong because post-CONTINUE delivery can fail and stack attribution can race.

**Needed:** Reproduce the safepoint scenario with a deterministic integration test and select one protocol based on evidence. Rewrite the design as current-state plus rejected alternatives; do not describe unmerged code as deleted. Specify event-delivery failure semantics, attribution accuracy, timeouts, daemon/JVM crash behavior, and whether the mechanism is safe for virtual-thread carriers and process-wide profiling.
