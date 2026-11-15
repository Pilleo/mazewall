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
  - "build.gradle.kts"
  - "portals/codegen/build.gradle.kts"
  - ".github/workflows/ci.yml"
target_symbols:
  - "dependencyCheckAnalyze"
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Investigate Codegen Yarn Audit I/O failure in Dependency-Check CI

**Context:**
In CI run `43760530358`, Security job `132954093915` verified trusted artifact `14176351241` at `2026-11-15T20:53:38Z`; the first six owner Analyze tasks completed with report inventory counts `2/2/6/5/4/6` and no listed findings, then Codegen Yarn Audit failed with a generic I/O label. A later run, `43771830610` / Security job `132989220151`, verified the same trusted artifact at `2026-11-15T22:55:02Z` (database `last.checked` `19:06:34Z`, age 3h48, within the unchanged four-hour gate); six owner analyses succeeded and Codegen Yarn Audit failed at 22:55:46. The bounded read-only observer reported `YARN_PACKAGE_JSON_MISSING` and a `NoSuchFileException` whose filename was the basename `package.json` in a Yarn context. It did not expose the exact resolved path or an HTTP status. A cached Kotlin Gradle plugin JAR contains `yarn.lock` without a sibling package manifest, but that does not prove the exception came from that exact sibling path. The actual missing-manifest path and why the analyzer requests it remain to be established; unrelated upstream Yarn Classic API incidents are not evidence of this failure. The temporary observer and its CI/policy wiring have been removed after this one observation; raw CI evidence and private copies are retained. Scope remains Gradle SCA/CI investigation only, with no application-code change or analyzer exception approved. The pending codegen-only exception proposal has not been approved, so all existing checks remain enabled. Preserve keyless scans, the trusted four-hour snapshot gate, thresholds, and failure propagation.

**Needed:**
1. Inspect the retained run `43771830610` task output and analyzer context to identify the exact path resolution that raised `NoSuchFileException`; distinguish an expected missing manifest from an incorrect/missing project input without assuming a Kotlin Gradle plugin sibling path or network/API failure.
2. Reproduce only the evidenced Codegen lifecycle with the actual Kotlin Gradle tooling lock and bounded diagnostics; make any eventual correction only at the build/CI SCA boundary, with no analyzer changes unless separately approved.
3. Keep the existing mandatory checks, report guard, keyless execution, trusted four-hour freshness gate, thresholds, and failure propagation in force. An exception proposal remains unapplied pending the user's decision.
4. Re-run Codegen and the seven-owner CI report guards; require the Codegen report and complete report inventory before claiming SDK scan coverage.
5. The one-run diagnostic init script and its workflow/policy wiring have been removed. Do not restore them or infer an analyzer change or exception from the synthetic controls without renewed approval.

---
<!-- id: issue-20261115-215930-225-investigate-codegen-yarn-audit-i-o-failure-in-depe  file: issue-20261115-215930-225-investigate-codegen-yarn-audit-i-o-failure-in-depe.md -->
