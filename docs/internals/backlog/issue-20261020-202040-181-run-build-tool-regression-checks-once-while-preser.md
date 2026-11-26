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
## Repository-review coordination — 2026-11-04

[BUILD-04](../reviews/2026-11-04-repository-review-findings.md#build-04) is owned by the
existing buildSrc140 scope: the three Kotlin JUnit tests are omitted by the root bridge. This
single-pass work and the proposed November2 smoke/full plan must preserve their real execution
and failure propagation rather than calling Java-only selection exhaustive. No new optimization,
threshold reduction or duplicate full-suite run was approved by the review.


## Implementation checkpoint — 2026-11-26

The required JDK 25 build-logic job and its executed 625-combination gate
projection are in place. The required check retains its existing name and
reports each lane's result through a typed evaluator. The core, PIT and
security jobs use one code filter that includes wrappers, locks and dependency
verification metadata. Publication also requires confined-kernel validation.
Optional integrations and opted-in Go remain prerequisites of the required
check under their existing policy.

The local policy fixtures checked the production command and its inputs,
including invalidation of a prior success marker and publication prerequisites.
The full offline host build and confined Docker kernel lane passed on JDK 25.
Hosted timing, runner resource data and publication qualification remain open.

---
<!-- id: issue-20261020-202040-181-run-build-tool-regression-checks-once-while-preser  file: issue-20261020-202040-181-run-build-tool-regression-checks-once-while-preser.md -->
