---
title: "Identify incomplete selected-launch retirement after interrupted close"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "portal"
target_modules:
  - ":portals:runtime"
target_files:
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/RequiredWorkerLaunchProfileTest.kt"
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
target_symbols:
  - "ProcessBroker"
open_questions: true
needs_kernel: true
core_lock: true
effort: "medium"
autonomy: "supervised"
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Identify incomplete selected-launch retirement after interrupted close

**Context:**
Separate read-only triage of hosted run39399933453. RequiredWorkerLaunchProfileTest selected launch cancellation and expiry retain actual child until physical retirement fails final broker.close at272 and retry at282 with IncompleteBrokerCleanupException. Exact remaining owner and cancel iteration are unknown. The3.4s testcase does not establish exhaustion of its5s close budget or a production leak. The failing test and lifecycle source are unchanged relative to the examined master. PR548 optional acceptance and mutation/memory checks pass but merge remains blocked. Do not expand the CI feature PR, retry blindly, raise timeouts or weaken physical-retirement assertions.

**Needed:**
1. Run one separately authorized owner-instrumented focused invocation with existing deadlines, retaining cancel iteration, startup cause, thread interruption and exact false retirement booleans.
2. Record maintenance, startup, dispatch, generation capture/cancellation, registry and attempt owner state plus actual child/socket/endpoint/pump physical status at both failed closes.
3. Only after locating the failed owner, add a deterministic regression and fix that exact boundary in this separate issue; retain all real process/FD/thread retirement and no guest-entry guarantees.

## Observed evidence and limits

[Run39399933453](https://github.com/Pilleo/mazewall/actions/runs/39399933453),
candidate `0e275fdd62d996f81dbde84f3cb7fb7a6ec79a69`, records the primary final
close failure and a suppressed retry failure. The separately corrected
ProcessBrokerIntegrationTest passes24/24 in this same run. The failing selected-
launch testcase takes3.4seconds and configures a5000ms close budget; this does not
show budget exhaustion. The cancellation/expiry iteration is not retained.

The generic close exception is created when cleanup throws no accumulated cause
but its completion conjunction is false. Capture which maintenance/startup/
dispatch/generation-capture/registry/attempt condition is false, with physical
child, FD and thread evidence. Result or future completion is not retirement.
Do not attribute another testcase's injected socket-close warning to this case.

This is a separately scoped investigation, not another PR548 source change.
Only documentation is created at this checkpoint; no reproduction or runtime
instrumentation has been executed. Instrumentation requires operator assignment;
production behavior changes require a measured cause and regression first.

---
<!-- id: issue-20261020-094438-599-identify-incomplete-selected-launch-retirement-aft  file: issue-20261020-094438-599-identify-incomplete-selected-launch-retirement-aft.md -->
