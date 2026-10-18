---
title: "Qualify SpotBugs analysis of generated Koog coroutine classes"
severity: "LOW"
status: "open"
priority: low
dependencies: []
component: "testing"
target_modules:
  - ":enforcer"
target_files:
  - "buildSrc/src/main/kotlin/mazewall.quality-conventions.gradle.kts"
  - "integrations/koog/build.gradle.kts"
needs_kernel: false
core_lock: true
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟢 [Severity: LOW]: Qualify SpotBugs analysis of generated Koog coroutine classes

**Context:**
The successful optional Koog build reports missing classes during `:integrations:koog:spotbugsMain`: new `io.mazewall.koog.ThreadToolAdapter$execute` and existing `io.mazewall.koog.PortalToolAdapter$execute$2`. The configured analyzer exits zero with `ignoreFailures=false`, but that does not establish complete generated-coroutine analysis. The new adapter-specific occurrence must not be described as entirely pre-existing or silently dismissed as full coverage.

This is a separately scoped tooling/evidence follow-up, not a demonstrated runtime defect or permission to weaken the analyzer. No analyzer/dependency configuration is changed in the thread feature. The legacy scaffolder's recognized module is coarse routing metadata; the explicit build/plugin paths define this future tooling scope.

**Needed:**
1. Reproduce and identify whether the missing generated-coroutine names arise from analyzer compatibility, classpath selection or generated-class naming; inspect the actual compiled classes rather than assuming a missing runtime dependency.
2. Correct the owning tooling/configuration with a focused regression proving meaningful analysis for both adapter shapes. Do not turn off the analyzer, set ignoreFailures, add broad exclusions or upgrade dependencies without approval.
3. Retain typed Detekt, host/native behavior and manual review as independent evidence, not substitutes for a claim that the missing-class diagnostic was fixed. Run the real optional build and configuration-cache checks after a correction.

## Investigation
- `.superpowers/sdd/2026-10-18-koog-thread-enforcement/optional-build-first.log:76-79` contains both exact names. The build exits zero and configuration-cache reuse subsequently passes.
- That workspace's independent `final-review.md:33-35` records the limitation and the unchanged quality convention's ignoreFailures=false setting. No current production source fix was requested by the review.

## Side effects
- Tooling-only follow-up: missing generated coroutine classes limit analyzer evidence; do not weaken ignoreFailures or change runtime behavior in the Koog feature.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20261018-132632  file: issue-20261018-132632-qualify-spotbugs-analysis-of-generated-koog-coroutine-classe.md -->
