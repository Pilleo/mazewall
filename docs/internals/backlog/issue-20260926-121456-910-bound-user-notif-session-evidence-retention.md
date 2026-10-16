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
2. Introduce session-wide retained-evidence budgets for events and stack samples, including payload size, plus an independently bounded/expiring path-deduplication cache.
3. Avoid copying the entire growing retained event list on every append while preserving thread safety and public collection compatibility.
4. Account for discarded evidence in coverage and keep listener ACK progress independent of retention capacity.

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
<!-- id: issue-20260926-121456-910-bound-user-notif-session-evidence-retention  file: issue-20260926-121456-910-bound-user-notif-session-evidence-retention.md -->
