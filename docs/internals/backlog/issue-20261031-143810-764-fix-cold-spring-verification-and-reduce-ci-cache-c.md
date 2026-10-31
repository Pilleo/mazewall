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
  - "buildSrc/build.gradle.kts"
  - "buildSrc/src/main/java/io/mazewall/build/TestHealthPlugin.java"
  - "buildSrc/src/test/java/io/mazewall/build/TestHealthPluginFunctionalTest.java"
  - "buildSrc/src/test/java/io/mazewall/build/FriendPathCacheFunctionalTest.java"
  - "buildSrc/src/test/java/io/mazewall/build/BtfRelocationFunctionalTest.java"
  - "buildSrc/src/test/java/io/mazewall/build/VerificationActionsTest.java"
  - "enforcer/build.gradle.kts"
  - "profiler/build.gradle.kts"
  - "portals/runtime/build.gradle.kts"
  - "portals/worker/build.gradle.kts"
  - "portals/codegen/build.gradle.kts"
  - "demos/cli-demo/build.gradle.kts"
  - "src/consumerVerification/kotlin/io/mazewall/verification/ConsumerFixture.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/OptionalIntegrationAcceptanceTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/PitchExamplesAcceptanceTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/IndependentConsumerCacheTest.kt"
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
- Initial fix at `d0fa7ea1` passed the full gate, independent review and hosted CI.
  Core-only warm qualification:1249s→519s, unit690s→34s; all unchanged host tests
  restored, native checks fresh. This did not qualify Optional warm efficiency.

Baseline master core1059s/optional1010s; green PR core575s/optional733s. The apparent
warm build-logic run was actually cold: its626MiB cache download hit GitHub's egress
account limit. Rebuilding the empty cached image cost about57–61s/job; no net saving
is claimed before hosted measurement. Snapshot splitting/quota changes and cached
native passes are not substituted for verification. Open pending managed landing.

## Approved Optional-lane follow-up

The operator requests a plan and fixes for all four review findings in PR563.
- [x] Real two-copy compilation/host-test reuse, changed-input invalidation and fresh
  native/presentation tasks; independent builders explicitly enable build caching.
- [x] Final root acceptance retains staging version/namespace and unchanged JAR bytes.
- [x] Typed friend files retain producer dependencies/internal access; relocated
  compilation reuses outputs, absent authority and changed internal APIs reject.
- [x] Diagnostic paths leave cache keys; cached tests do not attest fresh forks,
  while integration tasks execute each time.
- [x] Warm qualification exposed absolute source paths in generated BTF, changing
  profiler/plugin bytes across checkouts. Normalize debug paths only; real generated
  BTF/JAR relocation equality and changed-C-type invalidation pass.
- [ ] Focused/full gates, one independent review and Optional-only warm qualification.

Impact index was unavailable; exact caller/configuration reads and real regressions
supply evidence instead. No public SDK changes, new dependencies, broader friend
grants, timeout increases, cached native passes or separate execution journals.

---
<!-- id: issue-20261031-143810-764-fix-cold-spring-verification-and-reduce-ci-cache-c  file: issue-20261031-143810-764-fix-cold-spring-verification-and-reduce-ci-cache-c.md -->
