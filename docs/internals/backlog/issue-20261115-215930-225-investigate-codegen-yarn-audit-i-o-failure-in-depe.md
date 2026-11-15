---
title: "Investigate Codegen Yarn Audit I/O failure in Dependency-Check CI"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "portals/codegen"
  - "buildSrc"
target_files:
  - ".github/workflows/ci.yml"
  - "scripts/ci/security-scan-failure-diagnostics.init.gradle"
  - "buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt"
  - "buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt"
target_symbols:
  - "dependencyCheckAnalyze"
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Investigate Codegen Yarn Audit I/O failure in Dependency-Check CI

**Context:**
In CI run 43760530358, Security job 132954093915 verified trusted artifact 14176351241 at 2026-11-15T20:53:38Z. The first six owner Analyze tasks completed with report inventory counts 2/2/6/5/4/6 and no listed findings, then Codegen Yarn Audit failed with a generic I/O label. Exact cause is UNKNOWN and no HTTP status is confirmed; no Codegen report was produced. The cache-verified Kotlin Gradle plugin JAR contains yarn.lock but no sibling package manifest; this does not prove the failure cause, and independent upstream Yarn Classic API reports are not evidence for this task. Scope is Gradle SCA integration and CI diagnostics only; no application code. Node/Yarn analyzer remains unchanged. A temporary read-only init script adds bounded synthetic diagnostics and is required by CI policy/input checks for one real CI observation; the five synthetic controls preserve failure identity and safe labels, but do not prove the cause of the real Yarn failure. Remove the temporary observer after classifying that failure. Its `--init-script` wiring does not change the Gradle task, settings, or analyzer result handling. A codegen-only Yarn exception proposal is unapplied, the user decision is pending, and no exception is approved. Preserve keyless scans, trusted four-hour snapshot gate, thresholds, failures and checks by default.

**Needed:**
1. Retain and inspect the exact failing CI log plus Gradle task context, then identify the concrete failure boundary without inferring a network/API cause or reporting an HTTP status unless observed.
2. Reproduce the confirmed lifecycle with the actual Kotlin Gradle tooling lock and bounded Gradle diagnostics; make any eventual correction only at the build/CI SCA boundary, with no analyzer changes unless separately approved.
3. Keep the existing mandatory checks, report guard, keyless execution, trusted four-hour freshness gate, thresholds, and failure propagation in force. An exception proposal remains unapplied pending the user's decision.
4. Re-run Codegen and the seven-owner CI report guards; require the Codegen report and complete report inventory before claiming SDK scan coverage.
5. Remove the temporary diagnostic init script and its workflow/policy wiring after the one required CI observation has been classified; preserve the raw CI evidence and do not infer an analyzer change or exception from the synthetic controls.

---
<!-- id: issue-20261115-215930-225-investigate-codegen-yarn-audit-i-o-failure-in-depe  file: issue-20261115-215930-225-investigate-codegen-yarn-audit-i-o-failure-in-depe.md -->
