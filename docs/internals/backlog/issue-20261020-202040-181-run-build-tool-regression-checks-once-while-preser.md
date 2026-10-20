---
title: "Run build-tool regression checks once while preserving required security validation"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "ci"
target_modules: []
target_files:
  - "build.gradle.kts"
  - "buildSrc/build.gradle.kts"
  - ".github/workflows/ci.yml"
  - "buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt"
  - "buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt"
  - "buildSrc/src/test/java/io/mazewall/build/BuildLogicGateFunctionalTest.java"
  - "buildSrc/src/test/java/io/mazewall/build/VerificationActionsTest.java"
  - "buildSrc/src/test/java/io/mazewall/build/VerificationWorkflowInputFunctionalTest.java"
  - "docs/internals/designs/core/testing-strategy.md"
  - "docs/internals/build/gradle-baseline.md"
  - "CONTRIBUTING.md"
target_symbols: []
open_questions: false
needs_kernel: false
core_lock: true
effort: "medium"
autonomy: "supervised"
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Run build-tool regression checks once while preserving required security validation

**Context:**
Execute approved docs/internals/plans/2026-10-20-ci-rollout-and-build-logic.md on merged512565a7. Default security cold update/persistence/analysis and ten module-plus-marker uploads succeeded; warm/due, non-PIT memory, full dependency scope and immutable remote identity remain open, not inferred. Selectively recover heldf8f41c52 code only, never restore removed superpowers folders or overwrite mergedsecurity/workflowinput/rootfixture fixes. Explicit public GradleBuild rootcheck gate and independent required CI lane eliminate repeated jar-bootstrap regressions, with full local coverage and strict cache/input/failure/ordering proof. Preserve security as fourth literal-success prerequisite and existing thresholds/heaps/workers/native freshness. No new service/dependency/worktree/credential or benchmark republication.

**Needed:**
1. TDD selective local bridge and applied production DSL fixtures, including direct root/heavy/same-name ordering edges and workflow-input stale-marker failure.
2. TDD independent memory-qualified build-logic CI lane and 625 actual four-result join combinations, retaining all security/publish/cache controls.
3. Real executed-suite and reuse receipts, focused checks, complete included-module build and independent whole-change review.
4. Scoped PR and real hosted timing/runner/memory/native evidence; merge only through approval path with remaining security rollout gaps explicit.

## Approved scope and open rollout evidence

[Execution plan](../plans/2026-10-20-ci-rollout-and-build-logic.md) and
[actual default cold observations](../evidence/2026-10-20-ci-rollout.md) retain all
merged security and review fixes. The plan's local-start condition was explicitly
revised after the publication audit: safe local TDD/nonpublishing PR checks can
proceed after bounded cold evidence review, without manufacturing a benchmark
republication. Default warm/due evidence and broader resource/coverage assurances
remain open; this work package cannot close those gaps by implication.

Only port declared code from held `f8f41c52`; never merge that branch wholesale,
restore deleted folders, overwrite workflow-input/foreign-owner fixes, or remove
security from the new four-result join. Do not auto-archive the security repair,
Portal investigation or this work package on a green but incomplete observation.

---
<!-- id: issue-20261020-202040-181-run-build-tool-regression-checks-once-while-preser  file: issue-20261020-202040-181-run-build-tool-regression-checks-once-while-preser.md -->
