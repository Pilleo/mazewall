---
title: "Centralize global test-seam reset in a single JUnit extension"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Platform.kt"
  - "enforcer/src/test/kotlin/io/mazewall/testing/GlobalTestState.kt"
  - "enforcer/src/test/kotlin/io/mazewall/testing/ResetGlobalsExtension.kt"
  - "enforcer/src/test/kotlin/io/mazewall/testing/GlobalTestStateExtensionTest.kt"
target_symbols:
  - "Platform"
verify_cheap:
  - "./gradlew :enforcer:test --tests io.mazewall.testing.GlobalTestStateExtensionTest"
needs_kernel: false
core_lock: true
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: f18ebbc0-79e2-481e-8d19-7e282815d713
paperclip_identifier: MAZ-768
---

# 🟡 [Severity: MEDIUM]: Centralize global test-seam reset in a single JUnit extension

**Context:**
Test-mutable global state is scattered across production singletons, each with its own reset seam: `Platform.setProvider/resetToDefault/isCpuCetSupportedOverride` (`Platform.kt:55-77,221`), `BpfNativeCache.clear()`, `PolicyCompilationCache.clear()`, `MazewallEvents.clear()` + `failOnListenerError`, `ContainmentStateRegistry` process/thread state, `InstallSelfVerifier.verifiedPrograms`, `SupervisorDaemonManager.onUnexpectedExit`. Roughly 41 test files call `resetToDefault()/clear()` and 38 use `@AfterEach/@AfterClass`, but each test author must remember the *complete* set of seams for whatever globals their code path touches — forgetting one produces order-dependent tests that pass in isolation and fail (or worse, silently bypass containment assertions) in suite runs; this is why some suites already require `forkEvery = 1`. This is the classic junior-trap: nothing in the type system or test infra forces the cleanup.

**Needed:**
1. Create a single JUnit Jupiter extension (e.g. `io.mazewall.testing.ResetGlobalsExtension`) registered project-wide via `junit.platform.extensions.autodetection` (or a shared abstract base / `@ExtendWith` convention), that snapshots-and-restores every global seam in a fixed order after each test.
2. Expose one internal `GlobalTestState.resetAll()` used by the extension so new seams are added in exactly one place; make each singleton register its snapshotter there.
3. Migrate tests incrementally: first add the extension as a safety net (idempotent), then delete redundant per-test resets where they duplicate it.
4. Add an ArchUnit or lint rule failing when production code gains a new mutable `internal var` on an `object` without a corresponding entry in `GlobalTestState`.
5. Verify with `./gradlew :enforcer:test` run twice: once normally, once with randomized method order (`junit.jupiter.testmethod.order.default=random`) to prove order independence.

**Resolution (2026-09-06):** `ResetGlobalsExtension` is auto-detected for the enforcer test
runtime and calls `GlobalTestState.resetAll()` after each test. It resets the native-engine and
platform test overrides, native/policy caches, self-verification memoization, and diagnostics
listeners/mode. `Platform.resetToDefault()` now also clears the CET override. The reset
intentionally excludes `ContainmentStateRegistry`: its values model irreversible kernel filters,
and clearing them would desynchronize JVM bookkeeping from seccomp/Landlock state. A two-test
regression reproduces the pre-fix leak and proves cleanup. The full enforcer suite passed both
normally and under a forced randomized method-order run.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260826-102701  file: issue-20260826-102701-centralize-global-test-seam-reset-in-a-single-junit-extensio.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
