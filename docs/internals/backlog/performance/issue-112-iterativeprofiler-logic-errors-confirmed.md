---
title: "`IterativeProfiler` Logic Errors (Confirmed)"
severity: "HIGH"
status: "open"
priority: 0
dependencies: []
component: "profiler"
effort: "large"
---

# 🔴 [Severity: HIGH]: `IterativeProfiler` Logic Errors (Confirmed)

*   **Dimension:** State Machine Integrity & Failure Propagation
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt`
*   **Confirmed Proof:**
    1.  **Relative Paths:** `resolveAbsolutePath` explicitly returns `null` if the path does not start with `/`, causing a transition to `Failed`.
    2.  **Path Truncation:** `resolveAbsolutePath` backward scan stops at the first whitespace, truncating paths with spaces.
    3.  **Infinite Loop:** `updatePolicyForViolation` uses `path.startsWith(it.value)` which fails for disjoint prefix matches, leading to repeated denials of the same path.
    4.  **Context Loss:** Spawning a new `Thread` for each iteration loses `ThreadLocal` and MDC context, making diagnostics difficult.
*   **Needed:** Refactor `IterativeProfiler` to use a proof-of-progress state machine and proper path normalization.
