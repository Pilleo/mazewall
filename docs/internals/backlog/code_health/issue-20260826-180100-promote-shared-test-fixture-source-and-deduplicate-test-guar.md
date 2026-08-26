---
title: "Promote shared test fixture source and deduplicate test guards and fakes"
severity: "LOW"
status: "open"
priority: high
dependencies:
  - "issue-20260826-180041"
  - "issue-20260826-180053"
component: "testing"
target_modules:
  - ":enforcer"
  - ":profiler"
  - ":platform"
  - ":portal"
target_files:
  - "build.gradle.kts"
  - "src/sharedTest/kotlin/io/mazewall/testing/EnabledIfLinuxAndSupported.kt"
needs_kernel: false
core_lock: true
effort: "medium"
autonomy: "autonomous"
open_questions: false
has_side_effects: false
---

# 🟢 [Severity: LOW]: Promote shared test fixture source and deduplicate test guards and fakes

**Context:**
Test fixtures, assumption guards, and mocks are currently copy-pasted across multiple submodules and test suites:
- `@EnabledIfLinuxAndSupported` and `@EnabledIfCetSupported` are duplicated 3x across `enforcer/test`, `profiler/test`, and `profiler/integrationTest`.
- Mocks and test utilities (`MockNativeEngine`, `MockPlatformProvider`, `BaseIntegrationTest`, `NeedsFreshJvm`, `IsolatedProcessTester`) are duplicated or scattered.
- `:platform` and `:portal` hand-roll guard logic.
- SupervisorDaemonManagerTest and ProfilerDaemonManagerTest duplicate mirrored fakes.
We need to promote `src/sharedTest` into a first-class shared fixture source for all modules without introducing any new external dependencies.

**Needed:**
1. Configure `src/sharedTest` in `build.gradle.kts` as a shared test fixture source set available to `:enforcer`, `:profiler`, `:platform`, and `:portal`.
2. Consolidate `@EnabledIfLinuxAndSupported` and `@EnabledIfCetSupported` into a single canonical definition in `src/sharedTest`.
3. Move shared mocks and helpers (`MockNativeEngine`, `MockPlatformProvider`, `BaseIntegrationTest`, `NeedsFreshJvm`, `IsolatedProcessTester`) into `src/sharedTest`.
4. Wire `:platform` and `:portal` tests to use shared test guards and deduplicate daemon manager fake classes.
5. Verify via `./gradlew test` across all modules.

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260826-180100  file: issue-20260826-180100-promote-shared-test-fixture-source-and-deduplicate-test-guar.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
