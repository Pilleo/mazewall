---
title: "Bound stateless Portal worker replacement with an explicit restart budget"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies:
  - "issue-20261103-090026-112-add-explicit-portal-warm-up-through-existing-group"
component: "portal-runtime"
target_modules:
  - ":portals:runtime"
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/ProcessBrokerLifecycle.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntimeConfig.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRestartBudget.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalReplacementMachine.kt"
  - "portals/runtime/src/test/kotlin/io/mazewall/portal/PortalReplacementMachineTest.kt"
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/ProcessBrokerIntegrationTest.kt"
  - "docs/guides/portal-service-isolation.md"
target_symbols:
  - "ProcessBroker"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bound stateless Portal worker replacement with an explicit restart budget

**Context:**
Portal developer-experience plan P09: reuse the existing maintenance owner and pure lifecycle machines for an opt-in monotonic replacement budget; capacity restoration must remain independent from failed-call results and replay.

**Needed:**
1. Add an opt-in immutable `PortalRestartBudget(maxRestarts, window)` with a pure monotonic decision model, preserving current behavior when absent.
2. Enforce one bounded attempt after exact failed-worker retirement through the existing maintenance owner; retain all readiness and close/publication checks.
3. Transition exhaustion to Failed admission and expose the reason through existing health/failure diagnostics without changing the triggering call's failure.

**Plan and dependencies:**
- [Implementation plan P09](../../superpowers/plans/2026-11-03-portal-developer-experience.md) and [supervision contract](../designs/portal/portal-developer-experience.md#8-bounded-supervision).
- P05 supplies warm-up for final lifecycle races; the pure model can be developed against the current broker lifecycle.

**Acceptance:**
- Validate zero/negative limits, positive durations, exact window boundaries and monotonic time. Simultaneous worker failures cannot overspend the per-group budget.
- Every admitted spawn attempt consumes budget, including failed readiness. There is no automatic retry loop, failed-call replay or replacement of a lost singleton.
- Exhaustion stops admission/replacement while active, idle and provisional owners remain tracked for cleanup. Window expiry never revives a Failed group.
- Close racing replacement/warm-up permits no late publication or leaked process. Existing configuration constructors/default bridges retain their behavior.
- Preserve exit/cgroup-event evidence without assuming every crash is OOM. A replacement denied after initially successful warm-up fails under the existing policy and budget; no fallback or replay follows. Required OOM qualification uses real delegated finite child/parent limits.

**Verification:**
- [Junior implementation walkthrough: P09](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#p09--replacement-budgets-without-application-retries) separates pure clocked decisions, terminal exhaustion and real launch/retirement controls.
- Run `./gradlew :portals:runtime:test --tests '*PortalReplacementMachineTest'` and selected real-child `ProcessBrokerIntegrationTest` cases covering repeated failure and close races.
- Run `./gradlew build`, backlog validation and whitespace checks; document an explicit three-per-minute example and terminal recovery semantics.

## Repository review acceptance links — 2026-11-04 (P09)

The current close-versus-replacement ownership loss is independently owned by
[PRT-02](issue-20261103-235253-262-retain-replacement-worker-ownership-across-broker-.md).
Consume that correction before qualifying restart budgets: retain the exact acquired
replacement across rejected publication and failed first close, return bounded
incomplete cleanup, then successfully retry the same owner after release. A restart
counter cannot recover a discarded process owner. Keep local pure budget work
independent; PRT-02 owns the existing broker correction, and P09 owns the opt-in budget.

---
<!-- id: issue-20261103-090026-932-bound-stateless-portal-worker-replacement-with-an-  file: issue-20261103-090026-932-bound-stateless-portal-worker-replacement-with-an-.md -->
