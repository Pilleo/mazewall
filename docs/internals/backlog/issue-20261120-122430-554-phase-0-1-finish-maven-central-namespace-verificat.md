---
title: "Phase 0-1: Finish Maven Central namespace verification and close Phase 1 review items"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "docs/designs/first-process-lockdown-release.md"
target_symbols:
  - "ProcessFilterInstaller"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Phase 0-1: Finish Maven Central namespace verification and close Phase 1 review items

**Context:**
Phase 1 closure measurement and backlog triage are complete (GO for Option 2 at commit `ccbe82740325480b2e99d7bd9e92df7dc7680872`). D7 Maven Central namespace verification (`io.mazewall` or `io.github.pilleo`) and D8 final package/module naming sign-off remain open before Phase 6 publication.

## Phase 1 Baseline & Closure Evidence

- **Toolchain & bytecode target:** JDK `25.0.4.1.1` toolchain compiling to JDK 22 bytecode (`major version: 66` verified via `javap -v` on `ProcessPolicies` and `Arch`).
- **Focused host/kernel checks passed:** `./gradlew :platform:test :enforcer:test --rerun-tasks` (`platform:test`: 64 suites / 523 tests / 0 failures / 0 skips; `enforcer:test`: 202 suites / 1,654 tests / 0 failures / 2 assumption skips in `LandlockFailureModeTest`), `./gradlew :enforcer:integrationTest --tests "io.mazewall.seccomp.ProcessContainmentTest" --rerun-tasks` (30 tests / 0 failures / 0 skips), `./gradlew :enforcer:integrationTest --tests "io.mazewall.enforcer.api.execution.OwnedScopingNativeTest" --rerun-tasks` (2 tests / 0 failures / 0 skips). Full `./gradlew build` remains the Phase 4 completion gate.
- **Runtime `-verbose:class` closure (`ClosureProbe`):** Compiled and ran against only `platform-0.1.0-SNAPSHOT.jar`, `enforcer-0.1.0-SNAPSHOT.jar`, and `kotlin-stdlib-2.4.21.jar` (excluding `kotlinx-serialization`). Confirmed `VERIFIED_DENIED: Cannot run program "/bin/true": posix_spawn failed, error: 1 (Operation not permitted)`, zero `kotlinx-serialization` usage, zero daemon/USER_NOTIF listener startup, 100 `:platform` + 139 `:enforcer` classes loaded.
- **`jdeps -v` static closure:** Unmodified `ContainedExecutors` + `ProcessPolicies` reaches 1,494 bytecode classes; bypassing `ContainedExecutors` narrows to 385 bytecode classes (76 pre-trim source files, ~63 files after cutting the 4 seams S1–S4) with zero references to supervisor, execution, artifact, `sbob`, `kotlinx-serialization`, `platform.seccomp.daemon`, `platform.daemon`, `platform.socket`, `platform.process`, `ffi.networking`, or `ffi.supervisor`.

**Needed:**
1. Verify Maven Central namespace ownership (D7) and confirm final package/module naming (D8) while Phase 2 proceeds.

---
<!-- id: issue-20261120-122430-554-phase-0-1-finish-maven-central-namespace-verificat  file: issue-20261120-122430-554-phase-0-1-finish-maven-central-namespace-verificat.md -->
