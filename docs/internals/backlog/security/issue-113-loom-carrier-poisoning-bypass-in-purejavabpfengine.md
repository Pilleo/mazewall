---
title: "Loom Carrier Poisoning Bypass in `PureJavaBpfEngine`"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "medium"
---

# 🔴 [Severity: MEDIUM]: Loom Carrier Poisoning Bypass in `PureJavaBpfEngine`

*   **Dimension:** OS Invariants & Native Safety
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt`
*   **Observation:** The mandatory assertion `check(!Thread.currentThread().isVirtual)` is present in `ContainedExecutors` but absent in the public `PureJavaBpfEngine.install` methods. An advanced user could bypass the high-level API and poison carrier threads by calling the engine directly from a virtual thread.
*   **Needed:** Move the virtual thread check into `SeccompInstallationState.Uninitialized.lockPrivileges()` to ensure it is always enforced.
