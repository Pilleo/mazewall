---
title: "Promote shared test fixture source and deduplicate test guards and fakes"
severity: "LOW"
status: "resolved"
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
paperclip_issue_id: 87dc4c4b-d205-4def-89da-bc5f73770bf2
review_status: "approved_with_minor_fix"
review_date: "2026-08-27"
reviewer: "Mistral Vibe Agent (d159bcf4-4a01-4fd8-9007-bad4aababfeb)"
review_commit: "d99444d5f78e45cc3d9bf577690d81b6377b1079"
completion_date: "2026-08-27"
completed_by: "Vibe ACP Developer (d159bcf4-4a01-4fd8-9007-bad4aababfeb)"
pr_url: "https://github.com/Pilleo/mazewall/pull/523"
pr_merged_commit: "5c7d5c44 Merge pull request #523 from Pilleo/fix/shared-test-fixtures-1676116149387728033"
paperclip_identifier: MAZ-178
---

# ✅ [Severity: LOW]: Promote shared test fixture source and deduplicate test guards and fakes
**Status:** DONE - Completed via PR #523 (merged 2026-08-27)

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
