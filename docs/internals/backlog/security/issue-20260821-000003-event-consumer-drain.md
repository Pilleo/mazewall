---
title: "Wait for the event consumer before certifying the drain"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: PRRT_kwDOScnnEM6a9Pyp
---

# 🔴 [Severity: P1]: Wait for the event consumer before certifying the drain

**Context:** When EOF arrives after a burst has left events buffered in `eventQueue`, this marks the drain complete as soon as the socket reader finishes, even though `passThrough()` waits only five seconds for `collectorThread` and then interrupts it without confirming termination. If the consumer is still processing or abandons queued events, `Profiler.profile()` can observe `drainComplete=true` and `droppedEvents=0`, certify coverage as complete, and compile a policy that omits the queue tail.

**Problem:**
- `ProfilerTraceListener.kt:305` - Marks drain complete prematurely
- Buffered events may not be processed before drain is marked complete
- Coverage can be certified as complete when events are still in queue

**Impact:**
- Policy compilation can omit observed syscalls
- Incomplete Bill of Behavior marked as complete
- Security: unobserved syscalls may not be enforced

**Needed:**
1. Make completion contingent on the collector consuming the closed channel fully, or count abandoned buffered events as dropped.
