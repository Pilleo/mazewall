---
title: "`PureJavaBpfEngine` Thread State Synchronization"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: `PureJavaBpfEngine` Thread State Synchronization

*   **Dimension:** Cascading Failure Analysis
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt` (specifically `installOnProcess` and `threadState`)
*   **Failure Hypothesis:** The `PureJavaBpfEngine` uses a `ThreadLocal` called `threadState` to track the installation progress (e.g. `PrivilegesLocked`, `FilterBuilt`, `SystemCallApplied`). When `installOnProcess` is called, it installs a global seccomp filter using the `TSYNC` flag, affecting all sibling threads. However, it only updates the `ThreadLocal` state of the *calling* thread.
*   **Context & Proof:** In `installInternal`, the code calls `threadState.set(SeccompInstallationState...)` sequentially. Since `threadState` is a `ThreadLocal`, sibling threads that were just subjected to the `TSYNC` seccomp filter will still evaluate `PureJavaBpfEngine.state` as `Uninitialized`. If any sibling thread later attempts to verify its installation state or perform operations that check `state`, it will falsely believe no filter is applied.
*   **Cascading Risk Potential:** Medium diagnostic and internal state inconsistency. The global OS state diverges from the JVM's thread-local state map.
*   **Recommendation:** Document this state divergence, or implement a global `processState` alongside `threadState` so that `installOnProcess` correctly signals global containment.
