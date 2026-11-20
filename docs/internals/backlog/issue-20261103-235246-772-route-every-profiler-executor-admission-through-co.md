---
title: "Route every profiler executor admission through collector installation"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/api/Profiler.kt"
target_symbols:
  - "ProfilerExecutorWrapper"
open_questions: false
---

# 🟡 [Severity: HIGH]: Route every profiler executor admission through collector installation

**Context:**
Repository-wide review, 2026-11-04: [PN05-02](../reviews/2026-11-04-repository-review-findings.md#pn05-02). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category/confidence/boundary:** reproduced dispatch defect plus source-confirmed evidence consequence; high confidence; public optional profiler executor API. Coordinate the consequence with the profiler evidence/compiler lead.

**Trigger and evidence:** call `submit(Runnable, result)`, either `invokeAll`, or either `invokeAny` on a fresh `Profiler.ProfilerExecutorWrapper`. `profiler/src/main/kotlin/io/mazewall/profiler/api/Profiler.kt:94–127` delegates `ExecutorService` to the raw executor and overrides only `execute`, `submit(Callable)` and `submit(Runnable)`. The five other entry points dispatch directly to the raw delegate, not through the wrapper overrides. The isolated probe compiled **this current production file unchanged** with inert dependency stubs and observed installation counts `1,1,1,0,0,0,0,0` for those eight entry points.

**Effect:** normal public executor operations silently omit their workloads. If these are the only operations, `collectorRequested` is never set (`ProfilerSession.kt:57–69`); after closure, no listeners plus `!collectorRequested` satisfies drain (`ProfilerSession.kt:168–174`). `ProfilerExecutorSnapshotTest.kt:55–60` explicitly characterizes that unused-collector state as complete. The bug is not the valid empty-collector rule: real work bypasses the admission/installation path and is misclassified as unused. No native installation occurred in the probe; full profile consequence is a connected-source conclusion.

**Smallest correction/check:** route **every** task-admission entry through the same installation step, preserving overload results, cancellation and bulk deadlines. Add a counting-installer regression for all overloads on fresh workers; include rejected/failed installation proving no user task runs. One owned native bulk-submission case must capture a unique file operation and retain honest coverage. Do not solve this by pretending every empty profile is incomplete.

**Tracking/dependencies:** **NEW**; no matching profiler bulk/overload issue found in active or resolved backlog. The new issue should cover the public wrapper, service/session regression and coverage consequence together. It is independent of retention bounds and of native ACK fixes.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235246-772-route-every-profiler-executor-admission-through-co  file: issue-20261103-235246-772-route-every-profiler-executor-admission-through-co.md -->
