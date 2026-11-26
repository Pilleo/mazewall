---
title: "Retain profiler collection until accepted executor work ends"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
   - "profiler/src/main/kotlin/io/mazewall/profiler/api/Profiler.kt"
   - "profiler/src/main/kotlin/io/mazewall/profiler/api/ProfilerSession.kt"
   - "profiler/src/main/kotlin/io/mazewall/profiler/api/ProfilerService.kt"
   - "profiler/src/test/kotlin/io/mazewall/profiler/ProfilerExecutorSnapshotTest.kt"
   - "profiler/src/test/kotlin/io/mazewall/profiler/ProfilerServiceTest.kt"
   - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/ProfilerIntegrationTest.kt"
   - "docs/internals/designs/profiler/profiler-design.md"
   - "docs/internals/designs/api/profiler-public-api.md"
target_symbols:
   - "ProfilerExecutorWrapper"
   - "ProfilerSession"
open_questions: false
---

# 🟡 [Severity: HIGH]: Retain profiler collection until accepted executor work ends

**Context:**
Recursive state-design review C-01: wrapper shutdown closes its profiling session immediately after delegate shutdown, before accepted running/queued work ends. A normal pass-through terminal can permit complete evidence although tail work runs unobserved. Source-confirmed schedule, not newly runtime-reproduced; independent of admission-overload and pending-ACK owners.

**Needed:**
1. Separate admission stop, actual accepted-work end and collector retirement in one private lifecycle; preserve nonblocking shutdown and require actual work-end proof for completeness, with sticky incompleteness on early cutoff.

## Evidence and correction boundary

[Recursive review C-01](../../reviews/2026-11-05-state-complexity-review.md):
`Profiler.kt:139–142` closes collection immediately after `delegate.shutdown()`;
`awaitTermination` remains delegated at `:97`. `ProfilerSession.kt:168–174`
requires collector drain/cleanup but no accepted-work-end proof. Queued tasks
starting after session close skip installation at `:60–61`.

Hold a genuinely installed Callable on a latch with no pending event, request
shutdown, permit a successful pass-through terminal, then release it for a unique
file operation. That operation cannot enter the retired collector even when later
executor termination and snapshot predicates succeed. A queued accepted task is
another control. `ProfilerIntegrationTest.kt:419–449` already uses this ordering,
but checks successful late I/O/no ENOSYS, not tail evidence. No fresh runtime
reproduction or sandbox escape is claimed.

Use one private wrapper-owned `Accepting → Draining → WorkEnded → CollectorRetired`
lifecycle, with payloads for retained cleanup/cutoff evidence and pure decisions
emitting explicit effects. Keep `shutdown()` nonblocking; observe actual delegate
termination/accepted body end before normal collector retirement can certify the
submitted scope. Cancellation/Future completion/shutdownNow is not body-end proof.
Early service shutdown may cut collection off, but that incompleteness must remain
sticky even after tasks later terminate. Retain unfinished collector cleanup
ownership and permanent-filter pass-through; no public ownership-API redesign.

## Acceptance and verification

- Hold one installed Callable and one accepted queued task with deterministic
  barriers. While either remains live, no complete snapshot may certify that scope.
- Orderly shutdown must retain recording through both distinct late observations;
  actual work termination plus normal collector retirement permits completion.
- Explicit early service shutdown, timeout/interrupted await, failed installation
  and interrupt-resistant shutdownNow survivors cannot erase cutoff or physical
  ownership. Preserve unused-wrapper completeness and repeated/retryable cleanup.
- Extend the actual native shutdown test to assert late evidence, not merely
  successful I/O. Host fake-stream transitions check ordering; a disposable owned
  JVM qualifies permanent-filter/pass-through behavior. Run focused owner suites
  and `./gradlew build` at implementation completion.

## Ownership reconciliation

Coordinate the existing [all-admission-path owner](issue-20261103-235246-772-route-every-profiler-executor-admission-through-co.md)
and [pending-ACK owner](../issue-20261103-235247-255-keep-published-profiler-events-awaiting-ack-during.md).
Neither fixes accepted work after collector retirement: this schedule uses an
already-overridden Callable and no pending ACK. The [direct-worker interruption owner](../issue-20260926-121505-981-cancel-profiling-workloads-when-the-calling-thread.md)
retains its separate scope. The [resolved application migration](issue-20261011-230257-867-migrate-remaining-application-callers-to-owned-run.md)
already drains the raw demo pool before closing its collector; keep it resolved.
No duplicate implementation owner or mandatory unrelated dependency is introduced.

## Resolution — 2026-11-26

Wrapper shutdown stops admission and retires collectors after physical
executor termination. Awaiting termination shares one timeout with collector
retirement. Early service shutdown records an incomplete cutoff that later
work completion cannot clear.

The JDK 25 fixture proves an interrupt-resistant body still counts as live
after its Future is cancelled. The native test records distinct operations
from an already-running task and an accepted queued task after shutdown.

The complete offline host build and confined Docker kernel lane passed on JDK 25.
[Profiler executor lifecycle](../../designs/api/profiler-public-api.md#session-ownership)

---
<!-- id: issue-20261105-182827-544-retain-profiler-collection-until-accepted-executor  file: issue-20261105-182827-544-retain-profiler-collection-until-accepted-executor.md -->
