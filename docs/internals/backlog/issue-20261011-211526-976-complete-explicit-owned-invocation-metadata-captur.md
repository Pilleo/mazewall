---
title: "Complete explicit owned invocation metadata capture and restoration"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules: ["enforcer", "integrations:spring:mazewall-spring-boot"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
target_symbols:
  - "ManagedRuntime"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Complete explicit owned invocation metadata capture and restoration

**Context:**
Task 3 C8 requires opt-in caller capture, immutable declared metadata and finally restoration before owned worker reuse.

**Needed:**
1. Add explicit snapshot contract and prove owned placement, cancellation, nesting and restoration failure quarantine.

**Task 3 scoped WIP:**
- Explicit application capture decorates the existing Callable boundary; declared string metadata is copied immutably. Defaults and Spring's published context-reference contract are retained.
- Actual fixed owned platform workers exercise queue cancellation, caller mutation, alternation, one-permit exact nesting, undeclared-local exclusion, business/fatal identity, held restoration and failed-context quarantine. Physical end is never mocked.
- Actual kernel controls retain filesystem denial, caller access and captured worker retirement; existing Spring Security/H2 controls retain real caller-resource exclusion and transaction/authorization behavior.
- Native investigation: a failed Landlock generation may reject resource validation with `IllegalStateException` after prepared descriptor retirement, before the generation admission check can reject with `RejectedExecutionException`. Both fail closed with zero new body entry; the native control checks this real ordering.
- Scoped review regressions: a caught nested fatal must remain unsuccessful and retire after clean restoration; quarantine must not pre-deliver raw `AccessDeniedException` ahead of canonical caller classification. Both have retained failing assertion receipts and focused green controls; original denied/fatal cause and cleanup evidence remain intact.
- Evidence/report: operator evidence `/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-11-integration-contract-completion/task-3/task-3-report.md` and task-only `/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-11-integration-contract-completion/task-3/task-3-review.diff`. Scoped review and the combined completion gate remain pending; this issue stays open.

---
<!-- id: issue-20261011-211526-976-complete-explicit-owned-invocation-metadata-captur  file: issue-20261011-211526-976-complete-explicit-owned-invocation-metadata-captur.md -->
