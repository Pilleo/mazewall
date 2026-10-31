---
title: "Fix cold Spring verification and reduce CI cache churn"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "demos/spring-report-api/gradle/verification-metadata.xml"
  - ".github/workflows/ci.yml"
  - "build.gradle.kts"
  - "buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt"
  - "buildSrc/src/test/java/io/mazewall/build/VerificationWorkflowInputFunctionalTest.java"
  - "buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/CiEfficiencyTest.kt"
  - "docs/internals/designs/core/testing-strategy.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Fix cold Spring verification and reduce CI cache churn

**Context:**
Master run41157097057 fails on four unpinned BOM module files; warm PR cache hid the omission. User requests fix plus measured GitHub/Gradle efficiency and cache verification. Preserve mandatory tests/native freshness, approval/publication boundaries, security policy, resource limits and deadlines. No broad cache deletion, worktrees, new dependencies or execution diaries.

**Needed:**
1. Reproduce cold resolution before changes; verify upstream metadata and add only required SHA256 pins; measure job/task/cache costs and fix demonstrated waste with focused regressions; verify cold/warm behavior, full quality gate and independent scope review. Record final evidence briefly, not per-attempt logs.

## Outcome and verification

- Four missing module approvals reproduced cold, then pinned after Central/Portal/
  actual-resolved byte comparison and existing POM approval checks. Fresh resolution
  passes; a wrong cold checksum still fails. No versions or locks changed.
- Versioned runner cache refuses missing/failed/empty exports and saves only after
  successful export; obsolete demo-image handling is removed. Adapter cache proof
  now requires real reuse with no repeated task actions. Policy self-tests run once,
  not recursively in the workflow-input fixture; all status matrices remain.
- Coverage-agent path normalization preserves byte invalidation. Actual host and
  container enforcer unit runs have the same cache key; the latter is UP-TO-DATE.
- Focused regressions observed failures before fixes;51host harness tests and the
  buildSrc policy/input-invalidation suite pass. Full build/review/hosted gates pending.

Baseline master core1059s/optional1010s; green PR core575s/optional733s. The apparent
warm build-logic run was actually cold: its626MiB cache download hit GitHub's egress
account limit. Rebuilding the empty cached image cost about57–61s/job; no net saving
is claimed before hosted measurement. Snapshot splitting/quota changes and cached
native passes are not substituted for verification. Open pending managed landing.

---
<!-- id: issue-20261031-143810-764-fix-cold-spring-verification-and-reduce-ci-cache-c  file: issue-20261031-143810-764-fix-cold-spring-verification-and-reduce-ci-cache-c.md -->
