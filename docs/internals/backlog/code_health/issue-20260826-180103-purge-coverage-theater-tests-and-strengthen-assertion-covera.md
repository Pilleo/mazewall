---
title: "Purge coverage theater tests and strengthen assertion coverage"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20260826-180100"
component: "testing"
target_modules:
  - ":enforcer"
  - ":profiler"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/BpfBuilderCoverageTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/LinuxNativeCoverageTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/ProfilerCoverageTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/LandlockCoverageTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/SandboxDispatcherCoverageTest.kt"
verify_cheap:
  - "./gradlew :enforcer:test --tests io.mazewall.BpfBuilderCoverageTest"
  - "./gradlew :enforcer:test --tests io.mazewall.LinuxNativeCoverageTest"
  - "./gradlew :profiler:test --tests io.mazewall.profiler.ProfilerCoverageTest"
  - "./gradlew :enforcer:test --tests io.mazewall.LandlockCoverageTest"
  - "./gradlew :enforcer:test --tests io.mazewall.SandboxDispatcherCoverageTest"
needs_kernel: true
core_lock: false
effort: "medium"
autonomy: "autonomous"
open_questions: false
has_side_effects: false
---

# 🟡 [Severity: MEDIUM]: Purge coverage theater tests and strengthen assertion coverage

**Context:**
Several `*CoverageTest` files serve as coverage theater rather than meaningful behavioral specifications, containing hollow execution blocks or string/KDoc scanning tests:
- Hollow tests: `BpfBuilderCoverageTest.kt:14-28,119-135,137-144` (must assert emitted BPF instructions), `LinuxNativeCoverageTest.kt:88-114`, `ProfilerCoverageTest.kt:14-24` (add `fail()` on missing condition), `LandlockCoverageTest.kt:160-173`, `SandboxDispatcherCoverageTest`.
- Redundant/filler tests to delete: `BillOfBehaviorDtoCoverageTest`, `SeccompInstallationStateCoverageTest`, `TraceEventCoverageTest` echo matrices, KDoc-string-scanning tests in `ContainedExecutorsCoverageTest.kt:121-176`.
- Survivors should be renamed away from the misleading `*CoverageTest` suffix.
- Deleting filler tests risks dropping Jacoco instruction coverage below thresholds (enforcer >= 0.82, profiler >= 0.84); we must rewrite before deleting and verify `./scripts/check_coverage.sh` after each triage.

**Needed:**
1. Rewrite hollow tests in `BpfBuilderCoverageTest.kt`, `LinuxNativeCoverageTest.kt`, `ProfilerCoverageTest.kt`, `LandlockCoverageTest.kt`, and `SandboxDispatcherCoverageTest` to assert actual behavioral properties and emitted instructions.
2. Delete redundant coverage filler files (`BillOfBehaviorDtoCoverageTest`, `SeccompInstallationStateCoverageTest`, `TraceEventCoverageTest` echo matrices, and KDoc string scanners in `ContainedExecutorsCoverageTest.kt`).
3. Rename surviving `*CoverageTest` files to descriptive test names reflecting their actual domain responsibilities.
4. Verify Jacoco coverage thresholds via `./scripts/check_coverage.sh` and run full lint/test suites.

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260826-180103  file: issue-20260826-180103-purge-coverage-theater-tests-and-strengthen-assertion-covera.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
