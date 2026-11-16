---
title: "Retain unobserved confined drain sweep fatal failures before replacement"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/ManagedRuntime.kt"
target_symbols:
  - "ManagedRuntime"
open_questions: false
---

# 🟡 [Severity: HIGH]: Retain unobserved confined drain sweep fatal failures before replacement

**Context:**
D2 review R3: caller timeout or interruption can leave a fatal retry-clock error only in a completed sweep Future that later drain replaces without observing; earlier ordinary failure still prevents Complete.

**Needed:**
1. Reproduce with a bounded retry-clock barrier and interrupted caller, then retain fatal-first failures at the task boundary and in drain aggregation.

## Candidate verification

R3 `review-r3-red-001` reproduced the lost fatal sweep failure; the corrected regression passed in `review-owner-connected-green-static-003`, retaining fatal-first evidence.

Candidate gates in `b4-d2-implementation-001`: connected enforcer 268/268, platform 474/474 and profiler 32/32, with no skips or failures; static004 ratchet reported zero added warnings and no baseline growth. Rootful007 passed 8/8 and successor helper-regression-rootful001 passed 18/18; both postflight input manifests matched (1,829 checks each). Negative cases with injected effects are controlled tests, not proof of naturally occurring faults. Whole-B4 build002 was pending in the ledger; this issue remains open for controller settlement.

---
<!-- id: issue-20261105-213712-210-retain-unobserved-confined-drain-sweep-fatal-failu  file: issue-20261105-213712-210-retain-unobserved-confined-drain-sweep-fatal-failu.md -->
