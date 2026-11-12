---
title: "Investigate Tier E diagnostic shared-FD generation ambiguity across overlapping close and open"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupport.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupportTest.kt"
  - "docs/internals/backlog/README.md"
target_symbols:
  - "oracleFdLifetimes"
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Investigate Tier E diagnostic shared-FD generation ambiguity across overlapping close and open

**Context:**
Authenticated G5 run001 retains all 20+20 positive opens but remains INCOMPLETE for three FD4 generations: the conservative lastClose completion >= new open start predicate rejects overlapping intervals despite later successful closes. This is an evidence-completeness limitation, not proof the classifier is wrong or a cause of G4's missing events.

**Needed:**
1. Preserve G5 and the existing adverse overlap regression; establish whether retained kernel/ptrace observations can prove a unique shared FD generation without assuming syscall completion order or ignoring signal/untraced replacement uncertainty, before proposing any acceptance change.
2. Distinguish a previous generation's overlapping successful close from an
   overlapping close of an unestablished generation; retain failed/missing close,
   cross-thread reuse, dup/close-range/io_uring/exec and signal uncertainty controls.
   If exact history cannot be proved, retain INCOMPLETE and document the limit.
3. Any later change needs independently specified shared-path regressions and
   review. Do not delete the conservative predicate merely to make G5 pass, change
   the 20+20 workload/budgets, overwrite the original result or infer G4/A3 causality.

**Observed evidence:** G5 run001 has positive IDs `85048942395408`,
`85048942395450`, `85048942395452`, all fd4. Zero-based previous-close completion
versus next-open start comparisons are `26 >= 25`, `70 >= 69`, `73 >= 72`;
later successful paired closes are at32,73,79. The raw fixture correctly retains
INCOMPLETE under its current rule. Configuration TID545897 has signal observations,
so the existing chronology reconstruction deliberately refuses that thread.

**Retained investigation:**
`.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/a4-yield-diagnostic-run-001-fd-interpretation.md`
and `a4-g5-authenticated-incomplete-handoff.md`, with immutable raw evidence under
`a4-yield-diagnostic-run-001/`. This issue is queued investigation only; no repair,
replay or runtime retry is released.

---
<!-- id: issue-20261105-075010-660-investigate-tier-e-diagnostic-shared-fd-generation  file: issue-20261105-075010-660-investigate-tier-e-diagnostic-shared-fd-generation.md -->
