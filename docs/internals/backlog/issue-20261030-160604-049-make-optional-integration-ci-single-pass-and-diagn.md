---
title: "Make optional integration CI single pass and diagnosable"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "src/consumerVerification/kotlin/io/mazewall/verification/PitchExamplesAcceptanceTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/ConsumerFixture.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/ConsumerFixtureTest.kt"
  - "src/consumerVerification/kotlin/io/mazewall/verification/OptionalIntegrationAcceptanceTest.kt"
  - "buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt"
  - "buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt"
  - ".github/workflows/ci.yml"
  - "docs/internals/designs/core/testing-strategy.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Make optional integration CI single pass and diagnosable

**Context:**
Operator requests CI/build redesign on PR562, not more demo changes. Current copied acceptance executes native/presentation suites in lock, strict and cache-reuse phases; temporary nested reports are not uploaded; the final required aggregate omits optional integration. Keep all existing correctness, native freshness, security, ownership, SDK/publication identity and measurement assertions, limits and independent runners. No dependencies, new worktree, separate plan/journal, fallback, timeout increase or scanner-policy changes.

**Needed:**
1. Native inline plan:1 dependencies-only lock seed, one strict real consumer pass and cached same-graph dry-run with real forced-fresh counter regression;2 durable checkout-owned unique evidence plus failure-safe nested JUnit/HTML retention and safe allowlisted uploads;3 run new app checks earlier, all legacy checks unchanged and fifteen measurement forks last, require all six CI lanes in the final join with executable status-matrix tests;4 focused checks, one independent scope review, full build once and committed-source acceptance, compare observed execution counts/timings and update PR562 without merge. Track checklist and rulings in this scoped item, not separate artifacts.

## Outcome

- Fixed phases: dependency-only lock seeding; one strict real build; task-free
  post-build cache priming; identical task-free replay that MUST restore the cache.
  Priming handles Boot's newly created class directory without native re-execution.
- Earlier application feedback preserves legacy correctness. Operator direction
  later [removed pitch measurements](issue-20261030-225329-595-replace-pitch-only-measurements-with-optional-glob.md); no correctness assertion was dropped.
- Unique caller-owned diagnostics retain full logs/receipts and JUnit/HTML on
  failure. Source/destination path components reject aliases; only definite absence,
  not unreadable metadata, means reports do not exist. Original failures stay primary.
- The unchanged final aggregate requires six literal successes, rejecting failure,
  cancellation, skipping and missing results. Default-push publication prerequisites,
  independent runners, budgets and scanner policy remain unchanged.

Real Gradle execution-counter/cold-output, filesystem and shell-status regressions
passed. [Current CI/artifact qualification](issue-20261029-112338-058-qualify-copied-pitch-consumers-measurements-and-ho.md) confirms single strict native execution,
task-free cache replay and unchanged mandatory inventories. Run root/buildSrc
compilation serially because they share outputs.

Hosted run41110379828 reduced observed acceptance732→641seconds versus41082880463,
but optional-job wall time stayed860seconds: no end-to-end speedup claim. Release
publication and real cancellation delivery remain separate gates. Open pending managed landing.

---
<!-- id: issue-20261030-160604-049-make-optional-integration-ci-single-pass-and-diagn  file: issue-20261030-160604-049-make-optional-integration-ci-single-pass-and-diagn.md -->
