---
title: "Tie Spring lifecycle completion to physical retirement and retained dependencies"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "spring"
target_modules: [":integrations:spring:mazewall-spring-boot"]
target_files:
  - "integrations/spring/mazewall-spring-boot/src/main/java/io/mazewall/spring/DomainLifecycle.java"
target_symbols:
  - "DomainLifecycle"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Tie Spring lifecycle completion to physical retirement and retained dependencies

**Context:**
Repository-wide review, 2026-11-04: [SPR-02](../reviews/2026-11-04-repository-review-findings.md#spr-02). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · reproduced framework integration defect · high confidence · release boundary: optional Spring lifecycle/production adoption.**

**Trigger:** register a started `DomainLifecycle` with a normal Spring application context alongside destroyable worker dependencies, then close the context while the bounded `stop()` drain returns `DrainReport.Incomplete`. The same ownership can already be in DRAINING after an explicit application drain.

**Evidence:** `integrations/spring/mazewall-spring-boot/src/main/java/io/mazewall/spring/DomainLifecycle.java:95-110` sets DRAINING, retains an incomplete report, then calls the SmartLifecycle callback unconditionally. `isRunning()` is false in DRAINING, so subsequent framework stop processing skips it. The contract at `S1-S2-contract.md:5-12` intentionally describes the callback as bounded-return only, but that is not the upstream callback contract.

Spring's `SmartLifecycle.stop(Runnable)` callback means the shutdown procedure is complete; its lifecycle processor counts the callback down before proceeding to later phases and singleton destruction. Exact Spring 7.0.9 `DefaultLifecycleProcessor.doStop/LifecycleGroup.stop` also skips beans whose `isRunning()` is false. Application diagnostics cannot change those upstream semantics.

**Reproduction:** a real `GenericApplicationContext` with the freshly compiled current lifecycle, a simulated incomplete owner drain and a `DisposableBean` dependency reported:

```text
SMART_LIFECYCLE dependencyDestroyedWhileLive=true ownerRunning=false drain=Incomplete(unfinishedInvocations=1, liveWorkers=1, cleanupFailure=null)
```

This reproduces the framework callback/destruction ordering with simulated drain evidence; it is not a native-worker or actual pooled-datasource shutdown experiment. Existing `DomainLifecycleTest.java:15-44` only retries `stop()` manually outside context destruction. The held consumer probes similarly retain and close the runtime explicitly, so their success does not qualify ordinary Spring bean shutdown.

**Effect:** adopting this public SmartLifecycle bean with a conventional destroyable datasource/client can let Spring destroy dependencies while the protected worker still uses them, suppress the normal phase's pending-stop observation, and leave no framework retry for a live owner. Core-owned native descriptors remain retained; this is **not** a claim that restrictions are uninstalled. The HTTP demo deliberately keeps its database under its external bootstrap and only shuts it down after retirement (`demos/spring-report-api/.../ReportBootstrap.java:119-139`), which avoids that particular dependency destruction but does not repair the library callback contract.

**Smallest complete correction:** keep bounded manual drain reporting distinct from the SmartLifecycle completion notification. Notify Spring only after canonical retirement and preserve a pending-stop/owner path while draining. Specify and test an application owner that retains **both the runtime and its application dependencies** after Spring's configured shutdown phase timeout, until physical retirement permits datasource/client destruction. Merely withholding the callback does **not** stop framework timeout destruction; dependency ownership must survive that timeout independently. Do not replace one false guarantee with another or force-stop Java code.

**Regression/qualification:** a real context test with a held worker and destruction-observing dependency must prove no completion callback before Complete, eventual callback after release/retirement, refusal of new work while draining, and honest timeout ownership. Include explicit-drain-before-context-close and refresh-failure/unstarted-owner cleanup paths; the latter are requested coverage, not a separately proven FD leak here.

**Mapping:** **NEW concrete framework-contract mismatch** after targeted backlog/design searches. Related ownership requirements already live in `issue-20261008-033416-558` (bounded retained owners), `issue-20261008-072138-928` (typed-owner lifecycle) and resolved `issue-20261016-101505-976` (diagnostics). Their existing held/manual-drain receipts do not cover framework disposal ordering. Resolve the lifecycle policy before promising generic bean-managed shutdown; it does not block manually owned consumers that enforce dependency lifetime externally.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235255-089-tie-spring-lifecycle-completion-to-physical-retire  file: issue-20261103-235255-089-tie-spring-lifecycle-completion-to-physical-retire.md -->
