---
title: "Cascading Failure in `verifyInstallation` when stacking over a restrictive `prctl` filter"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: MEDIUM]: Cascading Failure in `verifyInstallation` when stacking over a restrictive `prctl` filter

*   **Dimension:** Cascading Failure / OS Invariants
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/seccomp/PureJavaBpfEngine.kt`
*   **Failure Hypothesis:** When a thread has an existing Seccomp filter that restricts `prctl` (e.g. `ACT_ERRNO`), installing a *subsequent* permissive policy that explicitly allows `prctl` will cause a deterministic crash during the installation verification phase.
*   **Context & Proof:** `verifyInstallation(definition: PolicyDefinition<*>)` checks if `prctl` is allowed by examining only the *incoming* `definition` (`definition.syscallActions[Syscall.PRCTL] ?: definition.defaultAction`). If allowed, it executes `LinuxNative.process.prctl(PrctlCommand.GetSeccomp)`. However, seccomp filters stack in the Linux kernel; the *most restrictive* action across all installed filters is applied. The incoming policy might allow `prctl`, but the *already installed* policy will intercept and block it, causing `r5.getOrThrow` to throw an exception and fail the installation of the second filter entirely.
*   **Cascading Risk Potential:** Medium. Prevents the installation of otherwise valid subsequent policies (stacking) if the initial policy was restrictive of `prctl`. This is a state tracking desynchronization between the thread's cumulative OS state and the stateless validation in `verifyInstallation`.
*   **Recommendation:** `verifyInstallation` should check the combined `ContainerState` from `ThreadStateRegistry` rather than just the incoming `PolicyDefinition`, ensuring it correctly respects the cumulative restrictions on `prctl` before attempting the native syscall.
