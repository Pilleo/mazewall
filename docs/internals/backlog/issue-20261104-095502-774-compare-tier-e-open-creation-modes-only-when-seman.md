---
title: "Compare Tier E open creation modes only when semantically used"
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
  - "docs/internals/backlog/issue-20261104-095502-774-compare-tier-e-open-creation-modes-only-when-seman.md"
  - "docs/internals/backlog/README.md"
target_symbols:
  - "joinObservedCalls"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Compare Tier E open creation modes only when semantically used

**Context:**
Cycle005 completed both fixture phases but all40coldjoins failed: MZOB retains JNI flags0/mode438 while ptrace reports O_RDONLY with no mode, decoded as0. Mode is unused without O_CREAT or full O_TMPFILE; glibc open64 initializes syscall mode0 and consumes varargs only when OPEN_NEEDS_MODE. Native raw records must remain unchanged, while creation-mode mismatches must still reject.

**Needed:**
1. Add behavioral regression for native0666 plus read-only oracle and retain mandatory exact creation mode comparison for O_CREAT/O_TMPFILE, input/flags/result/identity/closure checks; replay preserved cycle005 offline before fresh diagnostic.

**Follow-up J evidence:** Separate compiling behavioral RED/GREEN retained for
unused mode and exact creation-mode regressions. Actual cycle005 offline replay
now retains all40 native/oracle matches, but remains INCOMPLETE at multiple
marker SET receipts. This issue stays open pending controller review/fresh gate;
raw evidence and original outcome are unchanged.

---
<!-- id: issue-20261104-095502-774-compare-tier-e-open-creation-modes-only-when-seman  file: issue-20261104-095502-774-compare-tier-e-open-creation-modes-only-when-seman.md -->
