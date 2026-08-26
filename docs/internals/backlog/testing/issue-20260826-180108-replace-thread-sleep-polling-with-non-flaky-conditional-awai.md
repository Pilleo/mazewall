---
title: "Replace Thread.sleep polling with non-flaky conditional awaits in tests"
severity: "LOW"
status: "open"
priority: high
dependencies:
  - "issue-20260826-180100"
component: "testing"
target_modules:
  - ":enforcer"
  - ":profiler"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/ContainedExecutorsTest.kt"
verify_cheap:
  - "./gradlew :enforcer:test --tests io.mazewall.ContainedExecutorsTest"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "autonomous"
open_questions: false
has_side_effects: false
---

# 🟢 [Severity: LOW]: Replace Thread.sleep polling with non-flaky conditional awaits in tests

**Context:**
Several tests rely on arbitrary fixed `Thread.sleep(...)` durations for asynchronous completion or daemon synchronization, causing either unnecessary test delays or flaky timeouts in loaded environments.
We need to replace straightforward `Thread.sleep` polling with non-flaky conditional await loops using existing standard library constructs without introducing new external dependencies.

**Needed:**
1. Identify `Thread.sleep(...)` polling patterns in `enforcer/src/test/kotlin/io/mazewall/ContainedExecutorsTest.kt` and daemon integration/unit tests.
2. Replace with bounded predicate polling (awaitility-style conditional spin/sleep loop with fast interval and explicit timeout) using standard Java/Kotlin concurrency primitives (CountDownLatch, CompletableFuture, or custom await helper in `src/sharedTest`).
3. Verify test speed and determinism via `./gradlew test`.

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260826-180108  file: issue-20260826-180108-replace-thread-sleep-polling-with-non-flaky-conditional-awai.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
