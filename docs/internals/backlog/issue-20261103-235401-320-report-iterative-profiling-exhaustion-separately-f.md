---
title: "Report iterative profiling exhaustion separately from convergence"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/iterative/IterativeProfiler.kt"
target_symbols:
  - "IterativeProfiler"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Report iterative profiling exhaustion separately from convergence

**Context:**
Repository-wide review, 2026-11-04: [EVID-08](../reviews/2026-11-04-repository-review-findings.md#evid-08). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category/boundary:** reproduced bounded correctness/state-outcome defect; deprecated internal lab profiler, not the public session route. **Tracking:** **NEW** after iteration/exhaustion/progress searches; resolved issues `056`/`112` and bounded-wait `20260823-172000` concern other defects.

- `IterativeProfiler.kt:63–65,83–92` enters `Exceeded` after 20 unsuccessful iterations but returns `currentState.policy`, exactly as `Converged` does. The state type distinguishes failure internally and erases it at the return boundary.
- Trigger: twenty distinct denied paths, or repeated denial that policy growth cannot resolve. The repro injects twenty `AccessDeniedException`s, no successful workload execution, and receives an ordinary policy. `IterativeProfilerStateMachineTest.kt:97–117` currently codifies this outcome rather than proving convergence.
- Effect: lab callers cannot distinguish a learned policy from an exhausted partial policy; the final newly-added rule has not even been tried. No external consumer or production authorization misuse was established because the implementation is internal/deprecated.
- Correction: fail distinctly on exhaustion, retaining the partial policy/error as diagnostics, or use a closed internal outcome type. Do not return it as a converged result.
- Regression: exhaustion after zero successes, no-progress repeated denial, successful convergence, and non-containment failure propagation. **No core release dependency; should not be advertised as an equivalent fallback tracer.**

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235401-320-report-iterative-profiling-exhaustion-separately-f  file: issue-20261103-235401-320-report-iterative-profiling-exhaustion-separately-f.md -->
