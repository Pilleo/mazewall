---
title: "Own late-created strace descendants through cancellation and retirement"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/collector/StraceCollector.kt"
target_symbols:
  - "StraceProcessTree"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Own late-created strace descendants through cancellation and retirement

**Context:**
Repository-wide review, 2026-11-04: [EVID-06](../reviews/2026-11-04-repository-review-findings.md#evid-06). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category/boundary:** reproduced process-ownership defect; internal descendant-strace/floor tooling. **Tracking:** **EXPANDED follow-up** to resolved `docs/internals/backlog/resolved/issue-20260910-000104-own-strace-process-tree-lifecycle.md`; the earlier captured-child repair is real, but it does not cover late-created children.

- `StraceCollector.kt:139–155` snapshots descendants, signals the snapshot/root, waits, then takes a “late” snapshot **from the now-exited root**. A hook-created grandchild can already be reparented and is absent from both snapshots. `spawnAndRead():106–108` also only invokes final termination if the root is still alive.
- Trigger: an owned descendant's TERM handler starts a new helper and exits while the root also exits. Using the exact current `StraceProcessTree.terminate()` with two owned shells and a 12-second `sleep`, the method returned with root dead and the late helper still alive in state **S**, not merely a zombie. The repro then killed its owned helper separately. No strace attach or privileged tracing was performed.
- Effect: cancellation/timeout can leak a workload/helper process after the routine has discarded its ownership. The current test only proves killing a child captured before termination (`StraceProcessTreeTest.kt:15–40`).
- Correction: establish an owned cancellation domain **before launch** that also contains late inherited descendants (a qualified process-group/session mechanism, or equivalent existing process owner), and retain/report incomplete cleanup if retirement cannot be proven. Another snapshot of a dead parent is not a complete fix. Never signal the caller's ambient process group or invent ownership from an integer PID.
- Regression: a shutdown-hook/TERM handler that creates a child during teardown, plus already-captured TERM-resistant children, parent-first exit, interruption and bounded unsuccessful retirement. **Profiler-tooling unblock only; do not reopen the original resolved preexisting-child fix as if absent.**

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235400-690-own-late-created-strace-descendants-through-cancel  file: issue-20261103-235400-690-own-late-created-strace-descendants-through-cancel.md -->
