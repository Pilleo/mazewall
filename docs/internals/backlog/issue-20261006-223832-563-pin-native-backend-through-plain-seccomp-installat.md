---
title: "Pin native backend through plain Seccomp installation and verification"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt"
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/SeccompInstallationState.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/InstallationProgress.kt"
  - "enforcer/src/test"
  - "enforcer/src/integrationTest"
  - "enforcer/README.md"
  - "docs/internals/designs/enforcer/containment-design.md"
  - "docs/internals/backlog/issue-20261003-131111-752-investigate-intermittent-process-wide-profiler-int.md"
target_symbols: 
  - "PureJavaBpfEngine"
open_questions: false
---

# 🟡 [Severity: HIGH]: Pin native backend through plain Seccomp installation and verification

**Context:**
C3 native runtime capture: BPF packing, privilege locking, raw seccomp, prctl compatibility fallback, retries and mode verification currently re-read global LinuxNative. A backend replacement during a native stage can mix runtimes within one installation.

**Needed:**
1. Reproduce backend replacement during privilege setup/publication/compatibility fallback, capture one backend and propagate it through the existing state/engine pipeline without ambient overrides.

## Evidence — plain native capture slice

- Base revision `b69a5a84`; original C3 work, inline. Three runtime regressions reproduced global backend redirection during privilege setup, native publication, and ENOSYS/prctl compatibility fallback.
- The existing state/engine pipeline receives one selected `NativeEngine` explicitly through packing, privilege setup, raw installation, compatibility fallback, retries, and mandatory mode verification. No mutable provider setter, ThreadLocal override, parallel compiler, or dependency was introduced.
- `InstallationAttempt` captures its native backend once; CET and plain Seccomp consume that same view. A CET-lock callback replacing the global engine does not redirect subsequent verification or plain Seccomp setup; the corresponding host regression passes.
- The trait-selection architecture rule recognizes the existing immutable `snapshot()` accessor. A separate stronger rule prohibits mutable LinuxNative trait lookups within the plain engine/state classes. Renamed the internal native operation to `lockNoNewPrivileges` and migrated its callers so it cannot be mistaken for a singleton provider setter; no compatibility alias remains.
- Full enforcer/profiler host, ABI, architecture and warning-ratchet checks passed. One host command hit its external 120s deadline while compilation/tests were running; retained logs had no failure, and the unchanged 300s command completed successfully.
- `CapturedSeccompBackendNativeTest` replaces the global backend during real privilege setup, completes installation on the selected real backend, enters the target, and reads actual kernel mode 2 through that backend. One isolated-kernel test passed, zero failures/errors/skips.
- `./gradlew build integrationTest integrationTestFreshJvm` passed: 272 tasks, 84 executed, 188 up-to-date.
- First Docker gate hit the existing process-wide profiler missing-hostname/ACK errno=32/ENOSYS-cleanup failure recorded in `issue-20261003-131111-752-investigate-intermittent-process-wide-profiler-int.md`. An unchanged full Docker rerun passed in 46s; no profiler root-cause fix is claimed.
- This closes native backend selection for the plain/CET attempt stages and mandatory mode verification. Application-owned capability/runtime capture, remaining Landlock/supervised graph wiring, optional self-verification capture, and managed physical worker retirement remain original C3/C7 work. The issue stays open for repository review.

---
<!-- id: issue-20261006-223832-563-pin-native-backend-through-plain-seccomp-installat  file: issue-20261006-223832-563-pin-native-backend-through-plain-seccomp-installat.md -->
