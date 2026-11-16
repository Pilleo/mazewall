---
title: "Fix diagnostic target registration retention collision"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEAllTargetDaemonIntegrationTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupport.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupportTest.kt"
target_symbols:
  - "TierEAllTargetDaemonIntegrationTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Fix diagnostic target registration retention collision

**Context:**
The consumed pre-ARM attempt wrote target.reg before exercise, then mandatory export used CREATE_NEW for the same basename, causing FileAlreadyExistsException. Matching bytes do not complete export. Expected pre-ARM absent artifacts must be distinguished from unavailable complete evidence. See [result](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-discriminator-result.md), [closure review](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-discriminator-attempt-closure-review.md), [attempt log](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-discriminator-one-attempt.log), and [incident](issue-20261103-190440-355-investigate-retained-all-target-19-of-20-loadtempl.md). This file declaration is a future proposal, not implementation or acquisition authorization; the one attempt is consumed.

**Needed:**
1. Test first and make the minimum retention collision fix preserving no-overwrite, error handling, and source ownership; distinguish phase-expected missing artifacts from unavailable complete evidence without relaxing timeouts, counts, or permissions.

Continuation scope: the operator requested further autonomous progress after the consumed attempt. The current repair is host-only: separate the diagnostic startup registration receipt from the mandatory exported `target.reg`, exercise the actual shared receipt/export effects in tests, and preserve incomplete classification for missing pre-ARM artifacts. The added test-support paths are required by that caller/test seam. This does not authorize another diagnostic/kernel invocation, mutate retained evidence, or close the incident.

**Host follow-up evidence:** The startup copy now uses `startup-target.reg`; mandatory `target.reg` export retains strict create-new behavior. A behavioral host RED reproduced the collision and the corrected host tests cover private files, reuse/symlink refusal, and original-error preservation with missing pre-ARM evidence. Whole `:profiler:test`: 1650 tests, 0 failures/errors, 4 skipped; model wiring, integration compilation and unfiltered profiler ktlint passed. Details and limitations: [host report](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-follow-up-retention-report.md). This issue remains open pending normal integration/kernel qualification; no new acquisition was run.

---
<!-- id: issue-20261104-061908-412-fix-diagnostic-target-registration-retention-colli  file: issue-20261104-061908-412-fix-diagnostic-target-registration-retention-colli.md -->
