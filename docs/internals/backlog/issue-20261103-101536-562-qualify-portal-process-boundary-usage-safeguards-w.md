---
title: "Qualify Portal process-boundary usage safeguards with real consumers"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261103-090026-291-provide-bounded-portal-batching-over-explicit-batc"
  - "issue-20261103-090026-517-qualify-actor-style-stateful-portal-bindings-on-th"
  - "issue-20261103-090026-755-make-granted-portal-file-inputs-convenient-and-own"
  - "issue-20261103-090026-932-bound-stateless-portal-worker-replacement-with-an-"
  - "issue-20261103-101536-305-expose-and-validate-aggregate-portal-capacity-befo"
component: "portal-runtime"
target_modules:
  - ":portals:runtime"
  - ":portals:codegen"
target_files:
  - "tests/consumers/portal-plugin/README.md"
  - "tests/consumers/portal-plugin/build.gradle"
  - "tests/consumers/portal-plugin/src"
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/PortalUsageSafeguardsIntegrationTest.kt"
  - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalCodegenFunctionalTest.kt"
  - "docs/guides/portal-service-isolation.md"
target_symbols:
  - "PortalRuntime"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Qualify Portal process-boundary usage safeguards with real consumers

**Context:**
Portal developer-experience plan P13: executor-like syntax can hide copied values, absent caller context, persistent per-worker state, ambiguous completion, host-side codecs and multiplied process/queue memory. Existing lifecycle/codegen tasks need a coherent tested usage contract.

**Needed:**
1. Extend real generated Java/Kotlin consumers with distinct host/worker static and thread-local markers, an explicit correlation DTO, copy-at-submit checks and unsupported callback/resource controls.
2. Prove application-scoped runtime reuse and separate persistent per-worker stateless graphs. Use held concurrent calls rather than assuming round-robin scheduling; retained request/tenant authorization is not an acceptable cache.
3. Reuse existing queued-not-started, completed-business-error, unknown-completion, cancellation/retirement, state-loss and replacement controls. Guidance distinguishes evidence from rollback/idempotency and result delivery from physical end.
4. Demonstrate P06 finite result consumption, P12 pre-launch capacity rejection, required startup/denial diagnostics and explicit FD semantics. Explain that getters/codecs/constructors remain host work and completed futures may retain large result graphs.
5. Publish the design's usage matrix with enforcement ownership and P01's measured whole-deployment memory envelope. Use only authorized finite cgroup child/parent budgets for OOM controls; missing delegation is an unqualified lane, not successful proof.
6. Use a surviving controller to SIGKILL an application-host subprocess during startup, idle, a held/non-returning guest call, after a call leaves non-daemon guest work, and with a worker stalled in a shutdown hook. Record acquired worker PID/start identities, bound observation and rescue cleanup, and distinguish library cleanup from any explicitly configured deployment supervisor.

**Plan and scope:**
- [Implementation plan P13](../../superpowers/plans/2026-11-03-portal-developer-experience.md#p13--qualify-safe-process-boundary-use-in-real-applications) and [usage safeguards](../designs/portal/portal-developer-experience.md#10-process-boundary-usage-safeguards).
- `PortalUsageSafeguardsIntegrationTest.kt` is a planned new fixture. Existing P01–P09 and CG issues own behavior changes; this task joins their evidence in runnable consumer guidance.
- No automatic context/credential propagation, host callbacks, transaction/retry framework, spawn-per-call behavior or generic detection of application deadlocks.
- Abrupt-host-death coverage is a qualification gap, not a reproduced orphaning defect: [the driver](../../../portals/worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerDriver.kt) checks peer closure between dispatches, [main return](../../../portals/worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt) need not end guest-created threads, and [the existing physical-lifetime test](../../../portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/WorkerPhysicalReceiptLifetimeTest.kt) retains a live parent to reap the worker. A failing control creates a scoped corrective blocker against the existing launch/broker/worker owner.

**Acceptance:**
- The worker reports a distinct PID and independent static/thread-local state; only the explicit context value crosses. Mutation snapshots work and unsupported live/callback shapes reject before launch.
- Many binds/calls keep the configured process/constructor count. Stateless per-worker caches persist independently; singleton affinity/loss and group trust sharing are explicit.
- Unknown completion never replays work. Business exceptions do not promise rollback, cancelled results do not prove child death, and incomplete close retains cleanup ownership.
- Abrupt host termination has observed worker-death evidence within a declared bound for each claimed deployment mode. EOF, main return and containment-receipt retention are insufficient. Controller rescue kills fail qualification; a supervisor's tested guarantee is not presented as a library guarantee. Keep the lane open until any corrective blocker is fixed and requalified.
- Examples use finite producer/result windows and explicitly release completed handles/values. Heap caps, configured cgroup ceilings and measured RSS/PSS are separately labelled.
- Missing artifacts/profiles/delegation and real denied operations have useful stage/service/group causes. Health availability or a capacity report is never presented as readiness.
- The guide covers every design-section-10 surprise and distinguishes enforced checks from application responsibilities; real kernel/delegation evidence is required for claimed controls.

**Verification:**
- [Junior implementation walkthrough: P13](../../superpowers/plans/2026-11-03-portal-developer-experience-execution-guide.md#p13--real-consumer-usage-and-abrupt-host-death-qualification) defines all three process roles, validated barrier events, six control/scenario rows, acquisition-before-kill, finite observation/rescue, reparenting/identity hazards and failure triage. Suggested test budgets are explicit fixture settings, not a new public cleanup guarantee; rescue outcomes remain separate from qualification.
- Run `PortalUsageSafeguardsIntegrationTest`, relevant generated-source cases and strict packaged Java/Kotlin consumers. Retain real child identity, physical close, abrupt-host-death and required-denial evidence, including the controller's final rescue/cleanup outcome.
- Run `./gradlew build`, backlog/reference and whitespace checks at implementation completion; do not close native qualification on mocked or skipped controls.

## Repository review acceptance links — 2026-11-04 (P13)

Join the existing owners' real qualification rather than duplicate their fixes:
[PRT-01](issue-20261103-235252-762-isolate-cross-process-signals-in-the-required-port.md)
needs harmless owned-target cross-process signal refusal with self/JVM positives and
explicit kernel/outer-policy requirements;
[PRT-02](issue-20261103-235253-262-retain-replacement-worker-ownership-across-broker-.md)
needs retained replacement ownership across close/failed reap;
[PRT-04](issue-20261007-201438-646-expose-finite-portal-worker-admission-deadlines-an.md)
needs one close budget across maintenance and multiple worker retirement waits.
Compiled ALLOW verdicts and source interleavings establish the reported defects,
not passing native delivery/retirement controls. Retain the separate abrupt-host-death
matrix above; signal isolation and normal close do not establish that guarantee.

---
<!-- id: issue-20261103-101536-562-qualify-portal-process-boundary-usage-safeguards-w  file: issue-20261103-101536-562-qualify-portal-process-boundary-usage-safeguards-w.md -->
