---
title: "Replace pitch-only measurements with optional global benchmark workflow"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".github/workflows/benchmarks.yml"
  - "build.gradle.kts"
  - "src/consumerVerification/kotlin/io/mazewall/verification/BenchmarkWorkflowTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/OptionalIntegrationAcceptanceTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/PitchExamplesAcceptanceTest.kt"
  - "demos/koog-document-agent/build.gradle.kts"
  - "demos/koog-document-agent/README.md"
  - "benchmarks/integration-boundaries/README.md"
  - "README.md"
  - "docs/presentation/PRESENTER_GUIDE.md"
  - "docs/internals/designs/core/testing-strategy.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Replace pitch-only measurements with optional global benchmark workflow

**Context:**
Operator explicitly rejects the pitch-only benchmark and approves the smallest global alternative: workflow_dispatch only, existing integration-boundaries harness, raw results plus artifact/source/environment identity, no timing thresholds or PR-gate dependency. Also document it. Remove pitch script/tests, benchmark-only Kotlin entry/tasks and mandatory root/optional acceptance wiring; preserve current global harness, historical locks/evidence, profiler workloads and all independent correctness/security/lifecycle checks. Work in current checkout with inline scope/progress, no new frameworks/dependencies/worktrees or model calls.

**Needed:**
1. Add executable workflow freeze/manual-isolation regressions RED; add manual read-only workflow reusing existing producer/bootstrap/resource probe/harness/validator and bounded logs with always-uploaded diagnostics; remove pitch-only code and callers; update public guidance and testing strategy; focused regression suite, actual local bounded native recipe if prerequisites allow, one independent whole-branch review then final full build and push existing PR without merge/publication. Historical benchmark receipts stay historical, not new qualification.

## Outcome

Pitch scripts/tests, Kotlin measurement entry/tasks and mandatory benchmark calls
are removed. The manual-only, read-only workflow runs the existing boundary harness,
not new application/profiler suites; six-lane required CI and correctness remain.
It reuses source-bound local publications, format2 worker verification, frozen-input
hashes, bounded log sinks, the resource probe and raw validator. Native/permission
failures remain failures; historical locks are unchanged. Automatic identity capture
is not independently authenticated release custody or policy approval.

## Verification and remaining gates

Actual freeze/tamper regressions and all48host harness cases passed. The unchanged
workflow recipe ran locally at `539667b9b399e426f04e3ca53ef96e8bd0e2d4a5`: four modes,
eight warm samples each, actual retirement, matching hashes,80corruption controls,
exit0/pass resource receipts and no added OOM kills. Final full build with Spring
passed in10m49s; [required hosted CI and artifacts](issue-20261029-112338-058-qualify-copied-pitch-consumers-measurements-and-ho.md) passed at `0acbacd3`.

Independent whole-PR review found no Critical/Important issue; the download path
was corrected. [Known incident report bounds](issue-20261030-100713-290-qualify-and-package-the-useful-application-first-p.md) remain unchanged. The
[operator recipe](../../../benchmarks/integration-boundaries/README.md#optional-global-benchmark-job)
documents artifacts, retention, workload and limits. GitHub exposes dispatch only
after default-branch registration; hosted manual execution/upload/cancellation,
actionlint, production performance and release provenance are not qualified.
Open pending managed landing; no merge or publication was performed.

---
<!-- id: issue-20261030-225329-595-replace-pitch-only-measurements-with-optional-glob  file: issue-20261030-225329-595-replace-pitch-only-measurements-with-optional-glob.md -->
