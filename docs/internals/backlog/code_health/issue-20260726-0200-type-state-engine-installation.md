---
title: "Type-State Seccomp Installation Safety (Phantom Types)"
severity: "ENHANCEMENT"
status: "open"
priority: 5
dependencies: []
target_files: ["enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt"]
target_modules: [":enforcer"]
component: "enforcer"
effort: "medium"
autonomy: "supervised"
---

# 🔵 [Severity: ENHANCEMENT]: Type-State Seccomp Installation Safety (Phantom Types)

**Context:**
`PureJavaBpfEngine.kt` uses a manual try/catch sequence with intermediate val assignments and `updateState` calls. While `SeccompInstallationState` objects expose methods like `built.lockPrivileges()` that return the next state, the `PureJavaBpfEngine` implementation methods themselves (`setNoNewPrivs`, `installFilter`, `verifyInstallation`) are top-level `internal` functions that accept raw variables (like `prog: ManagedSegment`) rather than the strict `SeccompInstallationState` tokens.

**Problem:**
Because the methods don't strictly require the exact preceding state type as an argument, it's possible to accidentally bypass steps in the future (e.g., calling `installFilter` before `setNoNewPrivs`), violating the Linux kernel requirement that `no_new_privs` must be set before installing a filter. While tests would catch this at runtime with an `EACCES` or `EPERM` kernel error, it should be mathematically impossible to compile.

**Needed:**
Refactor `PureJavaBpfEngine`'s internal installation methods to require the previous `SeccompInstallationState` interface as a parameter (or receiver), using Phantom Types or the State Machine pattern to guarantee compile-time ordering.
For example, `installFilter` should explicitly require `state: SeccompInstallationState.PrivilegesLocked`, and `setNoNewPrivs` should require `SeccompInstallationState.FilterBuilt`.
