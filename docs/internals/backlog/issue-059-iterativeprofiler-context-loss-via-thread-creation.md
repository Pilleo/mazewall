---
title: "`IterativeProfiler` Context Loss via thread creation"
severity: "HIGH"
status: "open"
---

# 🔴 [Severity: HIGH]: `IterativeProfiler` Context Loss via thread creation

*   **Dimension:** DX
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt` (specifically `executeTask`)
*   **Failure Hypothesis:** When a developer profiles a workload that relies on `ThreadLocal` context variables (e.g. MDC logging, Spring Security context, or database transactions) using `IterativeProfiler.profile { ... }`, the profiler strips all this context, causing the workload to crash or behave incorrectly during the profiling run.
*   **Context & Proof:** In `IterativeProfiler.executeTask`, the task is executed by spawning a completely new thread: `val thread = Thread { ... task.run() }`. Standard `Thread` creation does not copy `ThreadLocal` variables from the parent thread. Consequently, when the task runs, any state initialized in the main thread is lost.
*   **Cascading Risk Potential:** High DX friction and compatibility risk. Breaks profiling for modern enterprise Java frameworks that heavily rely on thread-local contexts.
*   **Recommendation:** Use `InheritableThreadLocal` where appropriate, or allow the caller to pass a custom `ExecutorService` (like a Spring `TaskExecutor`) that implements context propagation, rather than raw `Thread` instantiation.
