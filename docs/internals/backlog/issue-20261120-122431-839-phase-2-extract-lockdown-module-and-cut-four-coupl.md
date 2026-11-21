---
title: "Phase 2: Extract :lockdown module and cut four coupling seams"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261120-122430-554-phase-0-1-finish-maven-central-namespace-verificat"
component: "core"
target_modules: [":lockdown", ":platform", ":enforcer"]
target_files:
  - "settings.gradle.kts"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/seccomp/BpfFilter.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/PolicyDefinition.kt"
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt"
target_symbols:
  - "ProcessFilterInstaller"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Phase 2: Extract :lockdown module and cut four coupling seams

**Context:**
Phase 1 jdeps -v measurement established a clean 63-file core closure for unsupervised process-wide lockdown after cutting four seams (S1 ContainedExecutors/Platform, S2 BpfFilter SupervisedKind, S3 PolicyDefinition/ProcessPolicies, S4 shared ContainerState).

**Needed:**
1. Create :lockdown subproject targeting JDK 22 bytecode, move the mapped platform/enforcer core files and tests, extract ProcessFilterInstaller with slim SeccompPlatformProbe, decouple BpfFilter SupervisedKind, and wire :platform/:enforcer to depend on :lockdown with friendPaths.

## Progress Evidence
- Cut all four coupling seams:
  - `BpfFilter.kt` decoupled from `SupervisedKind` and `SyscallNumber` via local `isSupervisableSyscall(nr, arch)` with parity test in `BpfFilterTest.kt`.
  - Created `SeccompPlatformProbe.kt`; updated `PureJavaBpfEngine.kt`, `SeccompInstallationState.kt`, and `ContainedExecutors.kt` to avoid heavy `Platform` dependencies on the lockdown path.
  - Extracted `PolicyScope.kt` and `RuntimeProfile.kt` helper boundaries in `:enforcer`; replaced direct `PolicyBuilder.setFilesystem` coupling.
  - Created `ProcessFilterInstaller.kt` and delegated `ProcessPolicies.denyProcessCreation` to it.
- Extracted `:lockdown` Gradle module (`lockdown/build.gradle.kts`, `settings.gradle.kts`) via `PrepareLockdownCoreSources` (zero project dependencies beyond `kotlin-stdlib`, targeting `--release 22` / JVM target 22, preserving `:platform` and `:enforcer` ABIs and `CLOSED_V1` 7-JAR deployment topology).

---
<!-- id: issue-20261120-122431-839-phase-2-extract-lockdown-module-and-cut-four-coupl  file: issue-20261120-122431-839-phase-2-extract-lockdown-module-and-cut-four-coupl.md -->
