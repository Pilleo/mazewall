---
title: "Investigate socket helper prewarm cleanup timeout during process spawn"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - "enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketHelperPool.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SocketCleanup.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorDaemonEngine.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorInstaller.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/ProcessSpawnStacktraceTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SocketHelperPrewarmTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SocketHelperProcessTest.kt"
target_symbols:
  - "SocketHelperPool"
  - "SocketCleanup"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Investigate socket helper prewarm cleanup timeout during process spawn

**Context:**
Investigate the retained helper prewarm cleanup timeout observed in the failed 338 process-spawn full build. The [failed-full report](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338-pre-return-full-build/failure-report.md) records ProcessSpawnStacktraceTest failure: the SupervisorDaemon child did not signal readiness while alive; child stderr reports `Socket helper cleanup timed out` at SocketCleanup.await from SocketHelperPool.prewarm; four SocketHelperMain starts were logged. The test directly enters SupervisorInstaller using a NATIVE_IMAGE deny-list with default ALLOW, explicit EXECVE/EXECVEAT NOTIFY and executable mappings allowed; it does not call ManagedRuntime or PreparedPath, and failure occurs before listener/filter handshake. See its [retained XML](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338-pre-return-full-build/xml/home/leanid/Documents/code/java/mazewall/enforcer/build/test-results/integrationTestFreshJvm/TEST-io.mazewall.enforcer.ProcessSpawnStacktraceTest.xml). The separate [focused process-spawn diagnostic](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task-7-338-process-spawn-diagnostic/report.md) passed one actual case, zero skips/failures, in 1m29s, but is not an explanation or fix and contains no individual helper-reap receipts. No direct changed capture call path is established, and shared-resource causality is unproven. Issues [139](issue-20261105-074955-139-b4-d1-implement-real-prestarted-confined-read-help.md) and [373](issue-20261105-175119-373-b4-d2-integrate-pre-install-confined-helper-readin.md) are broad references only; this issue tracks the specific prewarm cleanup/physical-helper ownership investigation. Preserve the existing shared five-second deadline and security behavior. Do not classify the failure as harmless/environmental or select a prewarm fix/general instrumentation before establishing physical helper ownership and timeout causes.

**Needed:**
1. Trace the actual prewarm caller, queued jobs, five-second deadline consumption, SocketCleanup.await failure path and helper process/pump/reap ownership against both the failed XML and focused diagnostic receipts.
2. Establish which timeout/cause and physical-retirement evidence can be supported; preserve UNKNOWN where receipts do not exist. Keep authorization behavior, deadline and fallback semantics unchanged during investigation.

**Bounded policy checkpoint (2026-11-12):** The implementation keeps the shared prewarm default at five seconds and permits an explicit, strictly validated `mazewall.supervisor.prewarmTimeoutMillis` value from 1 through 15000 milliseconds, forwarded only to the supervisor JVM. The separate helper connection, cleanup and parent readiness deadlines are unchanged. Focused host tests passed for the default, boundaries, invalid direct/managed startup and both manager routes; owning prewarm/manager and architecture checks passed, and the formatting corrections were checked separately. The intended failing assertion was observed before implementation. A 15000 ms owning-suite run and any full quality gate remain pending separate reviewed release; success with that opt-in would be a conditional-policy result, not proof that the default policy is reliable or that the startup cause is fixed. This issue remains open.

---
<!-- id: issue-20261109-054828-051-investigate-socket-helper-prewarm-cleanup-timeout-  file: issue-20261109-054828-051-investigate-socket-helper-prewarm-cleanup-timeout-.md -->


### Conditional full-build checkpoint — 2026-11-12

The [current full-build report](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/issue338-full-gate-detailed-report002.md) records a successful full build with 4,672 cases, 45 skips and no failures or errors; unit instruction coverage was 80,145/97,726 (82.009905%) and combined coverage was 80,572/97,726 (82.446841%). The owning FreshJvm task used the approved 15,000 ms configuration; full-run supervisor propagation was not sampled. The ordinary five-second default, issue 051 startup cause and default reliability remain unqualified. Three shutdown socket exceptions and three suppressed directory exceptions leave issue 752 resource retirement Unknown. Issues 051, 338 and 569 remain open: pre-typed raw/token ownership, VM and arena gaps, canonical dispatcher and custody limits remain; B5 helper capture requires its next preimplementation review. This conditional checkpoint does not close B5 or the wider roadmap. Earlier pending statements describe their historical checkpoints and are superseded only as to this full-build result.
