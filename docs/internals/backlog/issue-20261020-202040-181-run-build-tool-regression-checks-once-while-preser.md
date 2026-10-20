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
The complete build-tool regression suite currently runs during every fresh
buildSrc JAR bootstrap. Move it to an explicit local verification task and one
required CI lane, while keeping complete builds mandatory and preserving security,
native checks, verifier inputs and failure handling.

**Needed:**
1. TDD selective local bridge and applied production DSL fixtures, including direct root/heavy/same-name ordering edges and workflow-input stale-marker failure.
2. TDD independent memory-qualified build-logic CI lane and 625 actual four-result join combinations, retaining all security/publish/cache controls.
3. Real executed-suite and reuse receipts, focused checks, complete included-module build and independent whole-change review.
4. Scoped PR and real hosted timing/runner/memory/native evidence; merge only through approval path with remaining security rollout gaps explicit.

---
<!-- id: issue-20261020-202040-181-run-build-tool-regression-checks-once-while-preser  file: issue-20261020-202040-181-run-build-tool-regression-checks-once-while-preser.md -->
