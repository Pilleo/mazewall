---
title: "Implement test suite health listener and skip accounting"
severity: "LOW"
status: "resolved"
priority: high
dependencies: []
component: "testing"
target_modules:
  - ":enforcer"
  - ":profiler"
target_files:
  - "build.gradle.kts"
  - "src/sharedTest/kotlin/io/mazewall/testing/TestSuiteHealthListener.kt"
needs_kernel: false
core_lock: true
effort: "small"
autonomy: "autonomous"
open_questions: false
has_side_effects: false
---

# 🟢 [Severity: LOW]: Implement test suite health listener and skip accounting

**Context:**
Currently, tests that fail preconditions or assumptions silently skip without aggregated visibility into test tier erosion across modules.
We need skip-accounting visibility that never fails CI builds by default:
- Add a new `TestSuiteHealthListener` (implementing JUnit `LauncherSessionListener` / `TestExecutionListener`) in `src/sharedTest`: counts executed, skipped-by-assumption, and disabled tests per test class.
- Prints a prominent per-tier summary at session end and outputs `build/reports/test-tier-health.json`.
- Strict floor check is opt-in locally only via `-Pio.mazewall.strictTestTier=true` (deliberately NOT enabled or referenced by CI tasks to maintain zero new CI failure modes).

**Needed:**
1. Implement `TestSuiteHealthListener` in `src/sharedTest/kotlin/io/mazewall/testing/TestSuiteHealthListener.kt` (or register as a JUnit test engine/session listener).
2. Wire listener into `build.gradle.kts` test tasks to generate `build/reports/test-tier-health.json` and print summary.
3. Support local opt-in strict check via `-Pio.mazewall.strictTestTier=true`.
4. Verify that running standard `./gradlew test` prints health metrics and generates report without breaking CI.

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260826-180053  file: issue-20260826-180053-implement-test-suite-health-listener-and-skip-accounting.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
