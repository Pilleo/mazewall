---
title: "Fix Tier E diagnostic configuration cache serialization"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/build.gradle.kts"
target_symbols:
  - "tierEMissingOpenDiagnostic"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Fix Tier E diagnostic configuration cache serialization

**Context:**
The consumed one-attempt log records 3 configuration-cache problems with 2 unique descriptions involving Project and script-object serialization; the cache was discarded. This did not cause the BPF ATTACH error. See [result](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-discriminator-result.md), [closure review](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-discriminator-attempt-closure-review.md), [attempt log](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-discriminator-one-attempt.log), and [incident](issue-20261103-190440-355-investigate-retained-all-target-19-of-20-loadtempl.md). This file declaration is a future proposal, not implementation or acquisition authorization; the one attempt is consumed.

**Needed:**
1. Use actual model and configuration-cache host regressions without diagnostic Test execution; preserve both opt-ins, the exact selector, ordinary-build exclusion, no implicit native image preparation, and no retry.

---
<!-- id: issue-20261104-061917-747-fix-tier-e-diagnostic-configuration-cache-serializ  file: issue-20261104-061917-747-fix-tier-e-diagnostic-configuration-cache-serializ.md -->

**Current availability (2026-11-16):** The operator approved retiring the consumed `tierEMissingOpenDiagnostic` LIVE task under [retirement issue 079](issue-20261116-072155-079-retire-consumed-tier-e-a4-one-shot-live-diagnostic.md). Preserve this historical cache-failure record, but do not recreate or invoke that task under this issue. The separate replay path remains; this does not resolve the Tier-E cause or qualify G4. Keep this issue open for scope reassessment; no live retry is authorized.
