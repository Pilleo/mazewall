---
title: "Retain prelaunch failure evidence and cleanup in late-publication portal fixture"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "portal"
target_modules:
  - ":portals:runtime"
target_files:
  - "portals/runtime/src/integrationTest/kotlin/io/mazewall/portal/ProcessBrokerIntegrationTest.kt"
target_symbols: []
open_questions: false
needs_kernel: true
core_lock: true
effort: "small"
autonomy: "supervised"
has_side_effects: true
verify_cheap:
  - "./gradlew :portals:runtime:integrationTest --tests '*ProcessBrokerIntegrationTest.join timeout retains late process publication until it is reaped*' --console=plain --max-workers=1"
---

# 🟡 [Severity: MEDIUM]: Retain prelaunch failure evidence and cleanup in late-publication portal fixture

**Context:**
Local full build for PR548 failed the unchanged join-timeout late-publication fixture at launchEntered.await line221, before any physical-retirement assertion. The fixture sets both real prelaunch generation measurement and close/join budgets to100ms; early startup future failure is not observed, and no finally releases/joins/closes on barrier failure. Read-only diagnosis confirms consumer evidence helper is not on portal suite classpath and no production lifetime gap is demonstrated. Capture the actual early future cause, strengthen unconditional fixture cleanup, and separate setup from the deliberately100ms close budget only if supported by causal evidence. Preserve every real child/retirement assertion; no runtime policy changes or blind timeout increases.

**Needed:**
1. Run one focused diagnostic with original budgets, observing launcher entry or startup future failure and preserving actual cause and bounded cleanup.
2. If setup-budget expiry is confirmed, make fixture phase control deterministic while retaining100ms close/join semantics and real late child publication/reaping.
3. Verify the focused test, full owning integration suite, independent review and complete build; do not treat a passing rerun alone as a fix.

## Diagnostic and reviewed fixture correction

With both original100ms budgets retained, one focused diagnostic fails before
launcher entry with the actual `portal generation capture deadline expired` cause,
Failed lifecycle and zero provisional owners. This establishes an independently
reproduced setup-budget conflict; the older full-build XML did not retain its
cause, so its exact cause remains unknown.

`971053ab` uses the existing normal startup default for unrelated generation
measurement and keeps the under-test close/join100ms budget, five-second setup/
release bounds, real child launch and all original Failed/provisional/reaped/
Closed/zero-owner assertions. Early future failures retain their cause and finally
always attempts bounded release/join/close, preserving the primary test failure.
No production deadline, protocol or lifetime code changes.

Focused test and full owning integration suite pass (42 tests, zero failures,
one existing delegated-cgroup prerequisite skip), as does owning ktlint. Independent
review: spec PASS, quality PASS with a non-blocking Minor. If startup settlement
and broker close both fail during teardown, the secondary join exception may be
replaced by the close failure; the original test failure remains primary. Retain
that diagnostic follow-up here rather than claiming all exception paths complete.

Complete local optional build passes271tasks in4m30s. Hosted requalification is
pending; no memory/performance/native pass is inferred from these fixture checks.

---
<!-- id: issue-20261020-090040-415-retain-prelaunch-failure-evidence-and-cleanup-in-l  file: issue-20261020-090040-415-retain-prelaunch-failure-evidence-and-cleanup-in-l.md -->
