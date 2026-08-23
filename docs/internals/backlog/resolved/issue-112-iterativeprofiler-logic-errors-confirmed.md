---
title: '`IterativeProfiler` Logic Errors (Confirmed)'
severity: HIGH
status: resolved
priority: high
dependencies: []
target_files:
- profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt
target_modules:
- :profiler
component: profiler
effort: large
autonomy: supervised
solution_approved: false
blast_radius: medium
reversible: true
---

# 🔴 [Severity: HIGH]: `IterativeProfiler` Logic Errors (Confirmed)

**Context:**
*   **Dimension:** State Machine Integrity & Failure Propagation
*   **Target Area:** `profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt`
*   **Confirmed Proof:**
    1.  **Relative Paths:** `resolveAbsolutePath` explicitly returns `null` if the path does not start with `/`, causing a transition to `Failed`.
    2.  **Path Truncation:** `resolveAbsolutePath` backward scan stops at the first whitespace, truncating paths with spaces.
    3.  **Infinite Loop:** `updatePolicyForViolation` uses `path.startsWith(it.value)` which fails for disjoint prefix matches, leading to repeated denials of the same path.
    4.  **Context Loss:** Spawning a new `Thread` for each iteration loses `ThreadLocal` and MDC context, making diagnostics difficult.
*   **Needed:** Refactor `IterativeProfiler` to use a proof-of-progress state machine and proper path normalization.

**Needed:**
1. Implement a fix based on the issue description.

**Resolution (2026-08-23, verified against current code):**
1. *Relative paths fail* — stale. `IterativeProfilerTest.test iterative profiling converges on
   relative paths` passes; path extraction handles relative paths.
2. *Path truncation at whitespace* — stale. `parses path with spaces from generic exception
   messages` and the quoted-path variants pass.
3. *Disjoint-prefix startsWith* — resolved under issue-056 (hypothesis was stale; `Path.startsWith`
   is component-wise) and the check now uses the canonical `io.mazewall.core.isUnder` predicate with
   absolute normalization.
4. *Thread-per-iteration context loss* — partially valid, partially by design. A fresh thread per
   iteration is REQUIRED because seccomp filters are permanent for the OS thread lifetime; a
   less-restrictive next iteration can never run on an already-contained thread. The diagnosability
   gap was closed by naming worker threads `iterative-profiler-task-<N>` (`RealIterativeTaskExecutor`),
   making failures attributable in stack dumps and thread dumps. MDC/ThreadLocal replay was not added
   (no consumer in this codebase); if richer context propagation is ever needed it should be an
   explicit opt-in parameter to `executeTask`.
