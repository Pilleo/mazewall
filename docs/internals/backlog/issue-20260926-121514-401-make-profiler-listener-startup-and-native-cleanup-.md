---
title: "Make profiler listener startup and native cleanup exception-safe"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/internal/ProfilerTraceListenerTest.kt"
target_symbols:
  - "ProfilerTraceListener"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make profiler listener startup and native cleanup exception-safe

**Context:**
The native arena is allocated before worker startup but released only by worker finally; startup failure leaks it, and socket cleanup failure can skip arena release and termination signaling.

**Needed:**
1. Add injectable startup and allocation failure seams and assert exactly-once arena cleanup before and after worker handoff.
2. Make startup transactional: the initiating thread owns the arena until successful transfer to the worker and rolls it back on every earlier failure.
3. Use independent cleanup attempts/failure aggregation so socket-close failure cannot skip arena release or termination signaling; preserve fatal throwable identity.
4. Include collector-thread startup failure after reader startup in the rollback design, with no live orphaned reader or collector.

## Evidence and failure scenario

Static review on 2026-09-26; not yet runtime-reproduced:

- `ProfilerTraceListener.kt:145–171`: a shared native arena and input buffers are constructed before `Thread.start()`, with no initiating-thread rollback. If allocation, thread construction, or startup fails, the worker's `finally` cannot release the arena.
- `ProfilerTraceListener.kt:157–163`: socket cleanup precedes arena closure and termination-latch signaling; a fatal cleanup failure can skip the latter operations.
- `ProfilerSession.kt:85–99`: session ownership of the listener does not give it access to the locally scoped arena.

## Acceptance criteria and validation

- Inject failures in buffer allocation, reader thread construction/start, and collector startup without inducing real resource exhaustion.
- Every allocated arena is either transferred to a started worker or closed by the initiator exactly once.
- Inject socket-cleanup failure and verify arena release is still attempted and worker termination signaling still occurs; retain incomplete resource-cleanup evidence separately from thread termination.
- Preserve primary/fatal failures and retain retryable descriptor ownership; do not fabricate ownership to close raw integers.
- Extend `ProfilerTraceListenerTest` beyond normal EOF/graceful-close cases. Run focused listener tests, `:profiler:test`, and the merge gate; add kernel verification if protocol behavior changes.

## Related work and boundaries

Partial-initialization gap beyond [deterministic listener lifecycle](resolved/issue-033-profilertracelistener-lacks-deterministic-lifecycle-autoclos.md). Read `../designs/core/notification-liveness-contract.md` and the FFM safety guidance. Coordinate with the evidence-retention issue because both modify `ProfilerTraceListener`; keep any new injection seams internal and declare additional target files before execution.

---
<!-- id: issue-20260926-121514-401-make-profiler-listener-startup-and-native-cleanup-  file: issue-20260926-121514-401-make-profiler-listener-startup-and-native-cleanup-.md -->
