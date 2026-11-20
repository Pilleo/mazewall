---
title: "Bound USER_NOTIF session evidence retention"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/ProfilerSession.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/internal/ProfilerTraceListenerTest.kt"
target_symbols:
  - "ProfilerSession"
  - "ProfilerTraceListener"
open_questions: false
---

# 🟡 [Severity: HIGH]: Bound USER_NOTIF session evidence retention

**Context:**
The bounded event channel drains into unbounded retained events, stack samples, and path deduplication keys; CopyOnWriteArrayList also copies growing evidence on append.

**Needed:**
1. Add a continuously drained session retention test covering unique paths, repeated events, stack samples, loss accounting, and ACK progress.
2. Introduce session-wide retained-evidence budgets for events and stack samples, including payload size; bound/expire any retained deduplication cache without dropping policy-relevant distinctions.
3. Avoid copying the entire growing retained event list on every append while preserving thread safety and public collection compatibility.
4. Account for discarded evidence in coverage and keep listener ACK progress independent of retention capacity.
5. Preserve distinct access-mode/completeness evidence for repeated paths; path/name equality alone must not suppress an observation needed by compilation or coverage.

## Evidence and failure scenario

Static review on 2026-09-26; not yet runtime-reproduced:

- `ProfilerSession.kt:32–36`: retained logs, stack profiles, and path cache have no session-wide bound.
- `ProfilerTraceListener.kt:175–183`: the collector continuously appends events and stack samples after draining the bounded channel.
- `ProfilerTraceListener.kt:462–479`: deduplication timestamps change, but keys do not expire or evict.
- The reusable `Profiler.wrap(...)` API permits long-lived sessions. Distinct paths and repeated events outside the deduplication window accumulate even when queue occupancy remains low. `CopyOnWriteArrayList` additionally incurs quadratic cumulative copying as retained events grow.

## Acceptance criteria and validation

- Sustained input with a continuously drained queue cannot exceed declared retained-state budgets, including many unique paths and repeated stack-bearing events.
- All discarded observation evidence contributes to loss accounting and prevents complete coverage; cache eviction alone is not misreported as observation loss.
- Accepted protocol frames continue to receive the required ACK when retention is saturated, without blocking the reader on evidence storage.
- Snapshot/compilation and concurrent listener behavior remain consistent; existing public APIs are preserved unless separately approved.
- Extend retention/coverage tests alongside `TraceEventQueueTest`; its queue-overflow coverage alone does not establish downstream bounds. Run focused tests, `:profiler:test`, and the merge gate; include kernel verification if ACK/USER_NOTIF behavior changes.

## Related work and boundaries

[Bounded channel backpressure](resolved/issue-20260729-131007-profiler-trace-listener-bounded-channel-backpressure.md) bounds transport, not downstream session retention. [Tier E retention limits](resolved/issue-20260910-000105-bound-tier-e-retention-and-lifecycle.md) apply to a separate evidence pipeline. Read `../designs/profiler/profiler-design.md` and `../designs/core/notification-liveness-contract.md`. Coordinate with the listener-startup issue because both touch `ProfilerTraceListener`; declare any additional coverage/test files before execution.

---
## Repository-review revalidation — 2026-11-04

[PN-RETENTION](../reviews/2026-11-04-repository-review-findings.md#pn-retention): retained
event/stack/cache stores and cumulative copying remain this issue's scope. Queue and Tier E
limits do not bound USER_NOTIF session history. Preserve honest drop/completeness accounting
and retirement in overflow tests; no measured throughput or memory-size claim was produced.

## Recursive-review residual — PN-DEDUP, 2026-11-04

[PN-DEDUP](../reviews/2026-11-04-repository-review-recursive.md#pn-dedup)
adds a **P2, source-confirmed semantic-loss case** to this owner's existing cache
and discarded-evidence scope; the owner's high priority is unchanged.

`ProfilerTraceListener.kt:458–480,502–519` keys the 500 ms suppression window only
by syscall name and ordered resolved paths. With raw benchmark capture inactive,
same-worker OPENAT read (`flags=0`) then write (`flags=1`) on the same path within
that window loses the write before queueing. Both frames still receive ACK
(`:436–455`); no loss counter changes. `TraceEvent.kt:551–560` preserves those flags,
and `BobCompiler.kt:84–110` needs them to compile write capability. With otherwise
successful closure, `ProfilerSession.kt:159–179` can report complete coverage over
the retained read-only evidence. Later legitimate writes can be denied; no
overpermissive grant or native escape was demonstrated.

Remove policy-relevant early suppression, or preserve every compilation/coverage
distinction while compacting only optional stack samples. Keep retention budgets
and ACK progress. Add encoded same-path read→write and write→read controls inside
the window, O_PATH→read, incomplete-flags evidence, duplicate and shared-cache
controls. Assert both ACKs, retained write capability and honest completeness.
Current listener tests cover failed operands and path ordering, not this case.
The recursive review ran no regression/native test; existing loss-accounting
requirements justify extending this issue rather than creating a duplicate.

---
<!-- id: issue-20260926-121456-910-bound-user-notif-session-evidence-retention  file: issue-20260926-121456-910-bound-user-notif-session-evidence-retention.md -->
