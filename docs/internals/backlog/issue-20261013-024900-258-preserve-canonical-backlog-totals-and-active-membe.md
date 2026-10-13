---
title: "Preserve canonical backlog totals and active membership after evidence closure"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "docs/internals/backlog/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve canonical backlog totals and active membership after evidence closure

**Context:**
Full presentation build fails validateBacklogIndex: evidence checkpoint added parenthesized open/deferred detail inside the canonical summary line and retained resolved rows in the active table. ADK metadata checks pass, but the mandatory Gradle validator requires the exact summary grammar and active-only table membership. Preserve every historic link as explicitly archived history.

**Needed:**
1. Restore the validator-compatible total line and move resolved rows into visibly archived history without dropping links; run validateBacklogIndex and ADK checks before retrying the unchanged full gate.

---
<!-- id: issue-20261013-024900-258-preserve-canonical-backlog-totals-and-active-membe  file: issue-20261013-024900-258-preserve-canonical-backlog-totals-and-active-membe.md -->
