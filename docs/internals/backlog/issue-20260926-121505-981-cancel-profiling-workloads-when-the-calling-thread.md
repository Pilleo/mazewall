---
title: "Cancel profiling workloads when the calling thread is interrupted"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/ProfilerService.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/ProfilerServiceTest.kt"
target_symbols:
  - "ProfilerService"
open_questions: false
---

# 🟡 [Severity: HIGH]: Cancel profiling workloads when the calling thread is interrupted

**Context:**
Caller interruption during worker.join exits profile through session cleanup without interrupting or retiring the workload thread, allowing work to outlive cancellation.

**Needed:**
1. Add a deterministic test interrupting the profiling caller while its workload is blocked on an interruptible latch.
2. Give every exit after worker startup an explicit retirement path, including interruption during the initial wait, grace wait, and session-cleanup failure.
3. Request workload cancellation, safely retire the profiling session, perform a bounded grace join, and propagate cancellation with caller interruption preserved/restored.
4. Report and retain an explicit lifecycle owner for any surviving worker rather than silently losing it; do not use forced thread termination.

## Evidence and failure scenario

Static review on 2026-09-26; not yet runtime-reproduced:

- `ProfilerService.kt:68–108`, especially line 84: `worker.join(...)` is unguarded against caller interruption. The explicit `worker.interrupt()` occurs only in the elapsed-time timeout branch.
- `session.use` closes the profiling session on this exceptional exit but does not cancel the workload.
- A workload blocked in `CountDownLatch.await()` can remain alive after the caller is interrupted. It can continue side effects and, when it inherits non-daemon status, prevent JVM exit.

## Acceptance criteria and validation

- Interrupt a caller only after the workload has signaled it is running; assert workload interruption and termination without sleep-based coordination.
- Exercise interruption during grace handling and a session-cleanup exception; neither may bypass workload retirement.
- Exercise an interruption-ignoring workload with an explicit test release mechanism: cleanup returns within its budget, reports the survivor, and retains ownership until it exits. Ensure the test itself leaves no live worker.
- Preserve primary cancellation/fatal identity and attach cleanup failures without losing them; preserve permanent-filter pass-through shutdown semantics.
- Existing elapsed-time timeout behavior remains covered by `ProfilerServiceTest`. Run focused service tests, `:profiler:test`, and the merge gate.

## Related work and boundaries

Residual cancellation gap after [bounded blocking waits](resolved/issue-20260823-172000-bound-blocking-waits.md); the elapsed-time timeout already exists. Read `../designs/core/notification-liveness-contract.md` before changing cleanup order. Keep the public profiling API compatible; expand declared scope before changing another lifecycle owner.

---
<!-- id: issue-20260926-121505-981-cancel-profiling-workloads-when-the-calling-thread  file: issue-20260926-121505-981-cancel-profiling-workloads-when-the-calling-thread.md -->
