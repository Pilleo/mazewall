---
title: "Harden shared test evidence and build validation"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "testing"
target_modules: ["buildSrc", "enforcer", "platform", "profiler", "portal"]
target_files:
  - "build.gradle.kts"
  - "buildSrc/src/main/java/io/mazewall/build/FreshJvmClassSelector.java"
  - "buildSrc/src/main/java/io/mazewall/build/BacklogIndexValidator.java"
  - "buildSrc/src/main/java/io/mazewall/build/TestHealthAggregateAction.java"
  - "src/sharedTest/kotlin/io/mazewall/IsolatedProcessTester.kt"
  - "src/sharedTest/kotlin/io/mazewall/testing/TestSuiteHealthListener.kt"
  - "docs/internals/backlog/README.md"
open_questions: false
---

# 🔴 [Severity: HIGH]: Harden shared test evidence and build validation

**Context:** Test-health reports from multiple modules and forks overwrote one file, stale or cached output could look fresh, isolated children waited without a bound, fresh-JVM discovery searched arbitrary class bytes, and backlog validation checked only the summary line's shape.

**Needed:** Emit and aggregate fresh per-fork evidence, bound and reap isolated children, select fresh-JVM tests from class-file annotations, validate the complete backlog inventory, and correct native protocol guidance.

**Resolution (2026-09-10):** Test forks now publish atomic identity-bearing fragments after a repository-wide stale-evidence cleanup. Deterministic aggregation explicitly lists configured tasks without fresh evidence. Isolated children have validated deadlines and process-tree cleanup. Fresh-JVM selection reads class and method annotation metadata and follows inherited class tags. Backlog validation enforces totals, identity uniqueness, live links, status/location agreement, and exact active membership. BuildSrc executable contract tests cover each validator.

---
<!-- id: issue-20260910-030000-harden-shared-test-evidence-and-build-validation  file: issue-20260910-030000-harden-shared-test-evidence-and-build-validation.md -->
