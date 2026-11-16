---
title: "Join Tier E calls to the final observed marker state"
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
  - "docs/internals/backlog/issue-20261104-101631-824-join-tier-e-calls-to-the-final-observed-marker-sta.md"
  - "docs/internals/backlog/issue-20261104-101647-045-investigate-per-cpu-tier-e-uprobe-bpf-execution-fa.md"
  - "docs/internals/backlog/README.md"
target_symbols:
  - "joinObservedCalls"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Join Tier E calls to the final observed marker state

**Context:**
After J1/J2 correction, immutablecycle005 offline replay has40positiveinput/result joins and validproduction, but rejects each because eightcommitted sameinvocation SETreceipts precede entry. Controller decoded allcoldcalls: latestownedmarker across allIDs/stages is matchingSET; mainopen matches its exactsequence/generation with no intervening transition. SingleOrNull assumes oneprogram execution pernativepublication, not guaranteed by current perCPUperf-uprobe attachments.

**Needed:**
1. Select and validate the last unambiguous owned marker state at original-call entry from complete ordered receipts, preserving conflicting/clear/partial/identity/generation rejection and localizedmissing-main semantics. Add realistic repeated-SET and supersedingstate negatives; replay unchangedcycle005 before freshcapture.

Bounded implementation K changes only the six files above. J1/J2 stay preserved;
attachment/native/BPF changes belong to the separate fanout investigation. Keep
this issue open pending independent review, fresh cycle006 and the full gate.

---
<!-- id: issue-20261104-101631-824-join-tier-e-calls-to-the-final-observed-marker-sta  file: issue-20261104-101631-824-join-tier-e-calls-to-the-final-observed-marker-sta.md -->
