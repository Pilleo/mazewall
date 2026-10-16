---
title: "Trigger automatic failure triage for failed cached test tasks"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "build.gradle.kts"
target_symbols:
  - "runTriage"
open_questions: false
---

# ✅ [RESOLVED]: Trigger automatic failure triage for failed cached test tasks

**Context:**
Task 2 actual prctl simulator RED executed ten cases with four expected assertion failures and exit 1, but the unexcluded runTriage finalizer skipped because its onlyIf predicate returned false. Root build lines 731-743 derive Test.state.failure through a provider; exact configuration-cache/failure-state cause still needs meaningful reproduction. Task2 review marks diagnostics unresolved; preserve its valid GREEN/native receipts and repair the real trigger without any test/coverage/native waiver.

**Needed:**
1. Reproduce the failure-only triage trigger with a controlled real Gradle test failure and configuration-cache first/reused runs, identify the cause, then verify the smallest corrected wiring and successful-test no-triage control.


## Closure evidence — Task 9 controller draft

**Fifth, separately proposed resolved, 2026-10-11. Tested integrated producer:
`dc6f7bf44b68f494a4cb609702dc97c72fb892c4`.** This issue is not one of the four
Task 9 brief rows; its root file is open and **currently unregistered in the mixed
backlog index**. Controller approval/registration/count reconciliation is separate.

Accepted Task 2a proves the root cause: the configuration-cache provider froze
`Test.state.failure` before task execution. The correction uses a per-invocation
completion-event build service and selected-Test barrier, with visible bounded
event-delivery failure and the original failed Test/build preserved. Real direct
first-cache and reused-cache failures each execute **1 Test / 1 failure**, exit1,
with fresh **production collector JSON**; successful and up-to-date controls do not
start the collector. All **12 executable failure-triage fixture cases** are retained,
including pre-XML/crash/non-Test/parallel/delayed controls. Current combined bootstrap
also executes `buildSrc:verificationActionsTest`; its selected-build UP-TO-DATE reuse
is not another fixture execution or a new JUnit suite.

Receipts: accepted `task-2a-review.md`, `verification/task-2a-fix-direct-cc-{first,reuse}.*`,
`verification/task-2a-fix-direct-{success,up-to-date}.*`, `task-4-root-review.md`,
`verification/combined.log`, `task-5-6-review.md`. **Retained Minor:** the fixture's
fixed1.5s delay can flake and does not prove deterministic finalizer-wait entry;
replace it later with the bounded fixture-only handshake specified by that review.
The production event-wait fix is accepted; collector-self telemetry is not failed
worker/deadlock diagnosis or a guaranteed collector deadline.

---
<!-- id: issue-20261010-185539-634-trigger-automatic-failure-triage-for-failed-cached  file: issue-20261010-185539-634-trigger-automatic-failure-triage-for-failed-cached.md -->
