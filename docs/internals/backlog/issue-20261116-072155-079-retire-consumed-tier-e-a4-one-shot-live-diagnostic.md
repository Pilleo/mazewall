---
title: "Retire consumed Tier E A4 one-shot live diagnostic path"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules:
  - "profiler"
target_files:
  - "profiler/build.gradle.kts"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEAllTargetDaemonIntegrationTest.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEMissingOpenDiscriminatorIntegrationTest.kt"
  - "profiler/src/test/resources/tier-e-a4-tracer.py"
  - "profiler/src/test/resources/tier-e-a4-fingerprints.py"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupportTest.kt"
target_symbols:
  - "tierEMissingOpenDiagnostic"
  - "TierEAllTargetDaemonIntegrationTest"
  - "TierEMissingOpenDiscriminatorIntegrationTest"
  - "TierEA4DiagnosticSupportTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Retire consumed Tier E A4 one-shot live diagnostic path

**Context:**
The operator approved retiring only the consumed one-shot A4 LIVE diagnostic path and its two Python resources. The sole LIVE attempt is consumed; no further LIVE acquisition is authorized. This supersedes the earlier “no cleanup authorized” limit only for this narrow retirement. The cleanup must preserve `TierEA4DiagnosticReplay` and its support/provenance contract, historical packets and source records, the ordinary installed fixture and original 20+20 checks, shared pidfd/export safety, native safeguards, and closed defaults. Do not claim G4 completion, resolve or close issue 355 or its follow-ups, delete historical evidence, or touch native/production paths.

**Needed:**
1. Remove only the consumed `:profiler:tierEMissingOpenDiagnostic` task, its entry point/live-only branches and helpers, and the two A4 Python resources plus Python-specific controls in the scoped targets.
2. Preserve `TierEA4DiagnosticReplay` and its support and historical provenance contract, retained packets/source records, ordinary installed fixture and exact 20+20 workload, shared pidfd/export safety, native safeguards, and all closed defaults. Do not modify native or production paths.
3. Verify focused regressions and the final build without rearming the consumed LIVE attempt. Keep issue 355 and follow-ups open; G4 remains unresolved and this cleanup is not diagnostic qualification.

---
<!-- id: issue-20261116-072155-079-retire-consumed-tier-e-a4-one-shot-live-diagnostic  file: issue-20261116-072155-079-retire-consumed-tier-e-a4-one-shot-live-diagnostic.md -->
