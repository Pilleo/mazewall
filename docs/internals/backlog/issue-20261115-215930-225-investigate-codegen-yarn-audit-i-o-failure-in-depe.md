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
In CI run `43760530358`, Security job `132954093915` verified trusted artifact `14176351241` at `2026-11-15T20:53:38Z`; the first six owner Analyze tasks completed, then Codegen Yarn Audit failed with a generic I/O label. In run `43771830610` / job `132989220151`, six owner analyses succeeded and Codegen Yarn Audit failed at 22:55:46. A bounded observer reported `YARN_PACKAGE_JSON_MISSING` and a `NoSuchFileException` for basename `package.json` in a Yarn context, without exposing the full resolved path or an HTTP status. A cached Kotlin Gradle plugin JAR contains `yarn.lock` without a sibling package manifest, but that does not prove the exception came from that exact sibling path. The temporary observer and its CI/policy wiring have been removed; raw CI evidence and private copies remain.

**Approved scope (2026-11-16):** The user explicitly approved disabling Yarn Audit only for `:portals:codegen`. This disables Yarn Audit checks for all current and future Codegen Yarn inputs; other checks remain. It does not establish that the current lockfile is safe. JVM/other dependency checks and all other owner analyzers remain enabled. This exception does not qualify complete SDK dependency coverage, even if the Codegen report is produced. The four-hour snapshot gate, thresholds, fail-on-error behavior, report requirements, and failure propagation remain unchanged. Keep this issue OPEN to restore maintained Codegen Yarn coverage. Do not infer approval for a wider analyzer exception.

**Needed:**
1. Implement the approved Yarn Audit disable in `:portals:codegen` only. Add focused policy/functional coverage proving the exception cannot apply to any other project and that JVM/dependency-check analyzers for the other owners remain active.
2. Keep the approved limitation explicit: Yarn Audit checks are disabled for all current and future Codegen Yarn inputs; other checks remain. Do not mark the Codegen dependency graph or SDK scan complete on the basis of its remaining report.
3. Keep this issue OPEN and investigate a maintained Codegen Yarn advisory path that supports the actual lockfile/package-manifest layout; restore Yarn coverage only after the retained security checks and real CI report are verified.
4. Preserve keyless execution, the trusted four-hour snapshot gate, thresholds, `failOnError`, all other owner checks, and failure propagation. Do not widen the approved exception or restore the removed one-run diagnostic observer.

---
<!-- id: issue-20261115-215930-225-investigate-codegen-yarn-audit-i-o-failure-in-depe  file: issue-20261115-215930-225-investigate-codegen-yarn-audit-i-o-failure-in-depe.md -->
