---
title: "Reconcile Tier E ambient counters with inactive event semantics"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupport.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupportTest.kt"
  - "docs/internals/designs/profiler/tier-e-design.md"
  - "docs/internals/backlog/issue-20261104-095518-684-reconcile-tier-e-ambient-counters-with-inactive-ev.md"
  - "docs/internals/backlog/README.md"
target_symbols:
  - "productionIntegrity"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reconcile Tier E ambient counters with inactive event semantics

**Context:**
Cycle005 footer ambient_inactive_count2047 correctly matches2047 flag32 NO_ACTIVE_INVOCATION rows and2047 MZBD MAIN_INACTIVE rows. Another12567 flag4 NO_ACTIVE_INVOCATION rows match MAIN_NO_STORAGE; totalunattributed14614. Private classifier incorrectly equates ambient to all unattributed, causing false production/native-other-plane incompleteness. Closed zero-loss MZBD has20882committedrecords and production14922events/308resolved.

**Needed:**
1. Reconcile ambient count against its actual inactive reason and retained kernel receipt semantics, preserving totalcounts/identity/closure and malformed or mismatched evidence rejection. Add mixed-reason positive and counter/flag/stage negative regressions and offline replay of immutablecycle005.

**Follow-up J evidence:** Separate compiling mixed-reason behavioral RED/GREEN
retained, including baseline/localized paths and malformed/contradictory evidence
rejection. Actual cycle005 production comparison accepts ambient2047 against
2047inactive events/receipts, independently of12567no-storage events. Replay still
returns INCOMPLETE at multiple marker SET receipts, not production integrity.
Keep open pending controller review/fresh gate; no historical qualification claim.

---
<!-- id: issue-20261104-095518-684-reconcile-tier-e-ambient-counters-with-inactive-ev  file: issue-20261104-095518-684-reconcile-tier-e-ambient-counters-with-inactive-ev.md -->
