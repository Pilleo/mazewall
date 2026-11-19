---
title: "Own bounded platform worker generations and physical invocation completion"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [enforcer]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/InProcessDomain.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/DomainLimits.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/InvocationHandle.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/DrainReport.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedDomain.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/OwnedInvocation.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/GenerationLifecycle.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/RuntimeWorkerContext.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/execution/Deadline.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainmentFailure.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt"
   - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
   - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorInstallationLifetime.kt"
   - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorInstaller.kt"
  - "enforcer/src/test/kotlin/io/mazewall/execution/OwnedRuntimeTest.kt"
   - "enforcer/src/test/kotlin/io/mazewall/execution/GenerationLifecycleTest.kt"
   - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorInstallationLifetimeTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/OwnedRuntimeNativeTest.kt"
  - "enforcer/api/enforcer.api"
  - "enforcer/README.md"
  - "docs/internals/designs/enforcer/containment-design.md"
target_symbols: [ManagedRuntime, InProcessDomain, InvocationHandle, OwnedDomain, OwnedInvocation]
open_questions: false
---

# 🟡 [Severity: HIGH]: Own bounded platform worker generations and physical invocation completion

**Context:**
Approved integration foundation C7 requires application-owned trusted bootstrap, finite worker/admission limits, required installation before readiness, cooperative cancellation distinct from physical end, bounded drain and failed-generation retirement. Reuse the canonical installer and captured environment; never run rejected work on a caller or spawn replacements from restricted workers.

**Needed:**
1. Implement and verify the synchronous owned in-process runtime with native required-readiness and cancellation/drain regressions.

## Implementation and verification evidence

The runtime owns a trusted platform manager and fixed JDK worker generations. The process baseline is an explicit input; mapping-floor/runtime mismatch is rejected. One captured installation environment is retained across manager/worker setup. Every worker requires installation before protected-ready publication, and keeps its receipt registration through retirement. Definitions are frozen using the existing snapshot helper. Fixed worker/admission limits, a finite queue and AbortPolicy prevent caller-runs or shared-executor fallback.

Invocation delivery and physical end are separate minimal completion-stage views. Queued cancellation never enters the body and frees its admission; running cancellation holds the physical lease until the body ends. Cancellation is synchronized with worker association to prevent late interruption of another invocation. Drain stops admission and reports actual worker/receipt cleanup, with explicit incomplete close. Failed live generations cannot be concealed by replacement capacity; worker construction is restricted to the manager. Existing failure classification is extracted once and shared with the borrowed wrapper, not reimplemented.

Twelve host lifecycle cases and two pure generation cases passed without skips. The initial API baseline was missing (`run-0775cd80cfc5`); a live-failed-generation replacement regression failed before its guard (`run-bbf06cc1fba7`), and runtime-floor mismatch failed before validation (`run-4f079fa3c00c`). Cases cover overload, backend retention, cancellation, future-view mutation, drain, startup failure/fatal identity, foreign tokens, nesting rejection, healthy business failure and preparation timeout. Architecture tests retain exact closed outcomes and explicit named ownership-boundary generic-catch exceptions, backed by fatal/physical-lifetime tests; no broad package exemption was added. Warning budget stayed unchanged after simplifying the implementation.

One isolated actual-kernel case passed without skips: explicit HotSpot process baseline denies execve, both owned workers deny a fresh private-file read before readiness, caller file access remains available, and complete drain retires workers. Full host/profiler/ABI/quality checks passed. `./gradlew build integrationTest integrationTestFreshJvm` passed in 6m48s, 272 tasks (82 executed, 190 up-to-date), `run-9e8abf909387`. Serial Docker initially encountered recorded process-wide-profiler ACK-32 intermittency (`run-2bed8968d1cb`); unchanged full rerun passed in 53s (`run-48eca4d8ffb5`). An earlier full mapped Docker pass is `run-527b0e17a1b2`.

This slice is synchronous in-process ownership. C8 inline transitions/context/evidence, C4/C6 artifact bindings, Portal application ownership and framework adapters remain separate work. The issue stays open for repository review.

---
## Repository-review expansion — 2026-11-04

[LIF-01](../reviews/2026-11-04-repository-review-findings.md#lif-01) is a new P1 subcase:
a throwing matcher/exception accessor escapes classification inside OwnedInvocation's catch,
leaving delivery unresolved while end/drain report success. Regress the public owned-runtime
boundary with ordinary/fatal bodies and throwing diagnosis; preserve original and secondary
failure identity, settle both stages, and stop unsafe reuse before releasing pending ownership.
[LIF-03](../reviews/2026-11-04-repository-review-findings.md#lif-03) adds repeated explicit
failed-generation recovery: fully retired domains remain strongly retained after Complete drain.
Release only cleanup-successful physically retired heavy owners; retain stale-token semantics
and every live/cleanup-failed owner. Test callback/classloader reachability across recovery.
Current-source traces and existing-output mock-installation probes support these findings;
they do not supersede the dated acceptance above or supply native/release qualification.

## Owned-generation installation-health follow-up — 2026-11-05

[Recursive review B-02](../reviews/2026-11-05-state-complexity-review.md)
adds the missing listener-health-to-body-entry edge. `OwnedDomain.kt:97–119`
installs/requires its receipt once; later `OwnedInvocation.kt:137–148` enters the
body without the reusable-installation gate. `SupervisorInstaller.kt:128–139`
publishes listener failure only to `SupervisorInstallationLifetime.fail`.
A queued compute-only action can enter after that failure is published while the
domain remains locally READY. Permanent kernel filters remain installed; this is
stale readiness/authorization, **not a demonstrated forbidden-syscall bypass**.

- Consult the authoritative **current worker's** installation-health owner before
  protected body-entry authorization, including exact-domain inline entry. Reuse
  the existing generation failure path; do not reinstall, allocate another task
  session per body, or maintain a second writable healthy flag.
- Known failed health must retain its cause, quarantine the generation, prevent
  queued/new body entry and settle result/end without releasing running physical
  ownership early. Define the linearization boundary as failure published before
  authorization; a check cannot promise listener survival throughout later work.
- If immediate readiness revocation is needed, connect a narrow health event to
  the existing generation. Avoid shutdown/join while holding a supervisor monitor
  if that can re-enter or close the same owner. Preserve cancellation and actual
  receipt/thread/resource retirement.
- Deterministic barriers: hold body 1, queue body 2, complete post-readiness
  listener failure, then release body 1. Body 2 has zero entries, both promises
  settle, later/inline entry refuses, and held cleanup keeps drain Incomplete.
- Include healthy multi-action reuse without added filters/sessions, healthy
  business-failure reuse, original failure identity and cancellation-versus-health
  exactly-once accounting. Qualify real supervised channel loss in a fresh JVM,
  then focused owner tests and the required full build at implementation completion.

This source-confirmed schedule was not executed here. The
[idle-listener owner](issue-20261103-235357-491-keep-healthy-supervisor-listeners-alive-across-idl.md)
fixes one failure trigger, not health propagation after arbitrary channel loss.
Existing diagnosis/retention obligations above remain intact; no second listener
machine or mandatory idle-timeout dependency is introduced.

---
<!-- id: issue-20261007-092325-883-own-bounded-platform-worker-generations-and-physic  file: issue-20261007-092325-883-own-bounded-platform-worker-generations-and-physic.md -->
