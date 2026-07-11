---
title: "`IterativeProfiler` Context Loss via thread creation"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "profiler"
effort: "small"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: `IterativeProfiler` Context Loss via thread creation

**Context:**
**Hypothesis:** When a developer profiles a workload that relies on `ThreadLocal` context variables (e.g. MDC logging, Spring Security context, or database transactions) using `IterativeProfiler.profile { ... }`, the profiler strips all this context, causing the workload to crash or behave incorrectly during the profiling run.

In `IterativeProfiler.executeTask`, the task is executed by spawning a completely new thread: `val thread = Thread { ... task.run() }`. Standard `Thread` creation does not copy `ThreadLocal` variables from the parent thread. Consequently, when the task runs, any state initialized in the main thread is lost.


**Needed:**
1. Use `InheritableThreadLocal` where appropriate, or allow the caller to pass a custom `ExecutorService` (like a Spring `TaskExecutor`) that implements context propagation, rather than raw `Thread` instantiation.

## Solution Options

### Option A — Refactor implementation
Implement the recommendation described in the Needed section to resolve the issue directly. Target area: ``profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt` (specifically `executeTask`)`
**Pros:** Resolves the root cause of the issue.
**Cons:** Requires careful implementation and testing.
**Risk:** MEDIUM
**Effort:** small

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] Tests verify the fix works as expected.
- [ ] Issue is fully resolved in the codebase.

**Implementation Hints:**
- Ensure you read existing tests and implementation carefully before modifying code.
