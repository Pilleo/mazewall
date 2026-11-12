---
title: "Retire confined conversation after terminal accepted socket close failure"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperControl.kt"
target_symbols:
  - "ConfinedHelperControl"
open_questions: false
---

# 🟡 [Severity: HIGH]: Retire confined conversation after terminal accepted socket close failure

**Context:**
D2 independent review R1: platform publishes terminal native-close failure with empty retryable ownership, but control awaits a Complete callback that cannot occur and strands original resources and manager.

**Needed:**
1. Reproduce through actual platform cleanup and repeated runtime drain, then separate historical failure from published owner disposition.

## Candidate verification

R1 `review-r1-red-002` reproduced the stranded drain; `review-r1-green-001` passed 29/29. Published terminal disposal releases the conversation while preserving the original failure.

Candidate gates in `b4-d2-implementation-001`: connected enforcer 268/268, platform 474/474 and profiler 32/32, with no skips or failures; static004 ratchet reported zero added warnings and no baseline growth. Rootful007 passed 8/8 and successor helper-regression-rootful001 passed 18/18; both postflight input manifests matched (1,829 checks each). Negative cases with injected effects are controlled tests, not proof of naturally occurring faults. Whole-B4 build002 was pending in the ledger; this issue remains open for controller settlement.

---
<!-- id: issue-20261105-213711-642-retire-confined-conversation-after-terminal-accept  file: issue-20261105-213711-642-retire-confined-conversation-after-terminal-accept.md -->
