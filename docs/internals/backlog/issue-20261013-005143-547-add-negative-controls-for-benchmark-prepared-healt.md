---
title: "Add negative controls for benchmark prepared health guards"
severity: "LOW"
status: "open"
priority: low
dependencies: []
component: "benchmarks"
target_modules: []
target_files:
  - "benchmarks/integration-boundaries/src/main/kotlin/io/mazewall/benchmark/Boundaries.kt"
target_symbols:
  - "preparedHealth"
open_questions: false
---

# 🟡 [Severity: LOW]: Add negative controls for benchmark prepared health guards

**Context:**
Final scoped review Minor M1: correct health guards for instance mode, policy profile, positive singleton PID and STARTED lifecycle lack independent one-field negative controls. Existing raw candidate617 receipts remain valid; this is regression coverage debt.

**Needed:**
1. Add four one-field invalid health records to the existing non-native selectioncheck; require each to reject while retaining both-order positive group selection and equal-duration fresh timing acceptance.

**Acceptance:** All four individual guard removals would fail the selection check. Existing valid health records pass. No native benchmark replay or candidate/artifact relabel is needed for this host-only regression change.

---
<!-- id: issue-20261013-005143-547-add-negative-controls-for-benchmark-prepared-healt  file: issue-20261013-005143-547-add-negative-controls-for-benchmark-prepared-healt.md -->
