---
title: "Diagnose required worker rejection cause under CI cleanup timing"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: [":portals:runtime", ":portals:worker"]
target_files:
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/RequiredWorkerLaunchProfileTest.kt"
  - "portals/worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt"
  - "portals/worker/src/test/kotlin/io/mazewall/portal/worker/PortalWorkerStartupTest.kt"
target_symbols:
  - "ProcessBroker"
open_questions: false
---

# 🟡 [Severity: HIGH]: Diagnose required worker rejection cause under CI cleanup timing

**Context:**
CI run38757090872 now completes enforcer kernel suites and fails only RequiredWorkerLaunchProfileTest unsupported-contract assertion: broker startup fails but expected worker rejection reason is absent. The assertion omitted the actual exception and bounded child diagnostics. Ten repeated local runs of all five negative modes pass, and prior CI also observed retained incomplete cleanup. Preserve zero guest entry, ownership and specific-reason assertions; capture actual cause before deciding between lifecycle bug and fixture timing.

**Needed:**
1. Include actual startup exception in assertion diagnostics and inspect the next complete kernel JUnit artifact; do not accept generic failure or weaken cleanup.

## Established ordering and regression

Run38760726235 shows parent RPC reset (`errno=104`) with bounded diagnostics
containing only AUTH_PENDING. The worker closes its startup transport during
rollback before the default JVM uncaught handler can print the rejection; the
parent can then forcibly reap it, losing that diagnostic. A deterministic worker
unit test checks publication at the transport-close boundary and fails against
the old ordering (`run-fe6995fa3a75`).

The worker now prints a bounded startup reason before closing RPC, preserving
the original failure and fatal-first cleanup. This is diagnostic output only,
never authority/readiness or a fallback. The worker startup suite and actual
required-launch integration suite pass (`run-e547b593dc82`). The remote native
cases remain required for closure.
The complete included-module build also passes with this ordering
(`run-ba47640b04fd`,462080ms). All real rejection and cleanup assertions remain.

## Resolution

Commit `7bfe8c462300e2663c4752a4488508c721568e5c` passes the actual host and
kernel stages in CI run38766101235. Downloaded kernel JUnit evidence confirms
all seven applicable required-launch cases pass, including the original
negative-reason and physical cancellation/expiry retirement cases. The existing
owned-delegated-cgroup case is skipped because that optional authority is not
supplied on the hosted runner; it is not evidence of delegated-cgroup acceptance.
The next failure is the independently tracked platform mutation selector issue,
not worker rejection or cleanup (`run-a1d22b214df3`).

---
<!-- id: issue-20261015-152513-703-diagnose-required-worker-rejection-cause-under-ci-  file: issue-20261015-152513-703-diagnose-required-worker-rejection-cause-under-ci-.md -->
