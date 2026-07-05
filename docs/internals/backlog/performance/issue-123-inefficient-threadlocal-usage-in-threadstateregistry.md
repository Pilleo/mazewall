---
title: "Inefficient ThreadLocal usage in `ThreadStateRegistry`"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "enforcer"
effort: "small"
---

# 🔴 [Severity: PERFORMANCE]: Inefficient ThreadLocal usage in `ThreadStateRegistry`

*   **Dimension:** Performance & Efficiency
*   **Target Area:** `enforcer/src/main/kotlin/io/mazewall/enforcer/ThreadStateRegistry.kt`
*   **Failure Hypothesis:** Frequent access to `ThreadStateRegistry.state` during high-throughput executor task wrapping adds measurable overhead due to `ThreadLocal` lookups.
*   **Context & Proof:** `ContainedExecutors.resolveCurrentState()` accesses both `ThreadStateRegistry.state` and `ProcessStateRegistry.state`. In a tight loop (e.g., thousands of small tasks submitted to a wrapped executor), the continuous merging of Thread and Process states dominates the overhead.
*   **Cascading Risk Potential:** Performance. Increases the latency of task submission in `ContainedExecutors.wrap`.
*   **Recommendation:** Cache the merged `ContainerState` or optimize the `ThreadLocal` access patterns. Consider using Loom's `ScopedValue` when available.
