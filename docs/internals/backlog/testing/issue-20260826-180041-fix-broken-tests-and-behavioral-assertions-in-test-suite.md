---
title: "Fix broken tests and behavioral assertions in test suite"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "testing"
target_modules:
  - ":enforcer"
  - ":profiler"
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/ContainedExecutorsTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/PlatformTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/SbobUnicodeTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/iterative/IterativeProfilerTest.kt"
verify_cheap:
  - "./gradlew :enforcer:integrationTest --tests io.mazewall.enforcer.ContainedExecutorsTest"
  - "./gradlew :enforcer:test --tests io.mazewall.PlatformTest"
  - "./gradlew :enforcer:test --tests io.mazewall.SbobUnicodeTest"
  - "./gradlew :profiler:test --tests io.mazewall.profiler.iterative.IterativeProfilerTest"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "autonomous"
open_questions: false
has_side_effects: false
paperclip_issue_id: 812fa04d-cb3e-47fd-b5b1-c08888874997
paperclip_identifier: MAZ-769
---

# 🟡 [Severity: MEDIUM]: Fix broken tests and behavioral assertions in test suite

**Context:**
Several unit and integration tests contain broken assertions, improper setup, or behavioral mismatches that lead to test failures or hidden bugs:
- `enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/ContainedExecutorsTest.kt`: Assertion or executor verification logic requires fixing behavioral correctness.
- `enforcer/src/test/kotlin/io/mazewall/PlatformTest.kt:11-18`: Platform property / detection assertions have incorrect expectations or broken setups.
- `enforcer/src/test/kotlin/io/mazewall/SbobUnicodeTest.kt:15` & `profiler/src/test/kotlin/io/mazewall/profiler/iterative/IterativeProfilerTest.kt:20`: Profiler parsing/Unicode assertion assumptions fail under standard host-side runs.

**Needed:**
1. Fix broken assertions and test setups in `enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/ContainedExecutorsTest.kt` (lines 178-180).
2. Fix broken platform detection test cases in `enforcer/src/test/kotlin/io/mazewall/PlatformTest.kt` (lines 11-18).
3. Fix Unicode string and iterative profiler assertions in `enforcer/src/test/kotlin/io/mazewall/SbobUnicodeTest.kt` and `profiler/src/test/kotlin/io/mazewall/profiler/iterative/IterativeProfilerTest.kt:20`.
4. Verify host-side tests pass cleanly via `./gradlew test`.

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260826-180041  file: issue-20260826-180041-fix-broken-tests-and-behavioral-assertions-in-test-suite.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
