---
title: "Bound supervisor validation and daemon diagnostic retention"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorInstaller.kt"
target_symbols:
  - "SupervisorInstaller"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bound supervisor validation and daemon diagnostic retention

**Context:**
Repository-wide review, 2026-11-04: [SUP-05](../reviews/2026-11-04-repository-review-findings.md#sup-05). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · source-confirmed · high confidence · long-running supervised applications.**

**Trigger:** sustained successful or denied JVM validations, or sustained supervisor daemon output. Work can remain completely within the notification and helper concurrency limits.

**Connected evidence:**

- `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorInstaller.kt:387–396,563–564` builds and appends every raw validation stack to a global `ConcurrentLinkedQueue<String>`. No production consumer, eviction or lifecycle reset exists; the only drains found are in `enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/SupervisorProxyIntegrationTest.kt:510,574`.
- `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorDaemonManager.kt:91,212–218` retains every child output line in another unbounded queue. At daemon failure, lines 136–145 iterate the complete history and copy it into the exit event.

**Consequence:** retained heap grows with total historical operations/output, not current admitted work. This can exhaust the application heap during otherwise bounded, long-lived use; the crash-report path also traverses/copies the accumulated history. The linear retention is directly established; no throughput or time-to-OOM claim is made.

**Smallest correction:** remove the test-only global stack retention from the production path, or use an explicitly owned bounded diagnostic sink. Bound daemon log history by count/bytes, including per-line limits, and report dropped entries. Preserve a bounded useful failure tail.

**Regression:** drive substantially more validations/log lines than the chosen bound, assert retained bytes/count remain bounded and recent failure context remains available, then retire/restart owners and check old history is not accumulated. Exercise oversized individual lines and repeated failure reporting.

**Mapping/dependency:** **NEW supervisor-specific defect** after active/resolved searches for both queue names and bounded/unbounded log retention. `docs/internals/backlog/issue-20260926-121456-910-bound-user-notif-session-evidence-retention.md` is the separate profiler retention issue. Plan B6 lines 259–261 already calls for bounded diagnostics and no default raw stack/path logging; extend that requirement to existing supervisor use. Independent of live-capture implementation and required for a meaningful long-running memory qualification.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235359-257-bound-supervisor-validation-and-daemon-diagnostic-  file: issue-20261103-235359-257-bound-supervisor-validation-and-daemon-diagnostic-.md -->
