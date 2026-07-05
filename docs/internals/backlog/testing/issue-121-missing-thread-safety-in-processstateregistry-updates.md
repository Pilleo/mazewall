---
title: "Missing Thread-Safety in `ProcessStateRegistry` Updates"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: MEDIUM]: Missing Thread-Safety in `ProcessStateRegistry` Updates

*   **Dimension:** Cascading Failure Analysis (The Systems View)
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/ProcessStateRegistry.kt` and `ContainedExecutors.kt`
*   **Failure Hypothesis:** Concurrent calls to `ContainedExecutors.installOnProcess` or `updateProcessState` might lead to lost updates or race conditions when merging policies if `ProcessStateRegistry.update` is not atomic.
*   **Context & Proof:** In `ContainedExecutors.kt`, `updateProcessState` calls `ProcessStateRegistry.update { current -> current.withNewSeccompPolicy(...) }`. If `ProcessStateRegistry` uses a simple volatile variable without compare-and-swap (CAS) or synchronization, concurrent process-wide installations could overwrite each other's state, leading to an inconsistent view of the global containment policy.
*   **Cascading Risk Potential:** Medium. In a multithreaded environment, simultaneous installations could result in a policy state that does not reflect the actual OS-level stacked filters, causing `verifyInstallation` or future filter planning to fail.
*   **Recommendation:** Ensure `ProcessStateRegistry.update` uses a synchronized block or `AtomicReference.updateAndGet` to guarantee atomicity of state transitions.
