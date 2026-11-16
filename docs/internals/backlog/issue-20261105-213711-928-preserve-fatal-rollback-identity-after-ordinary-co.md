---
title: "Preserve fatal rollback identity after ordinary confined helper setup failure"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorInstaller.kt"
target_symbols:
  - "ConfinedHelperSession"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve fatal rollback identity after ordinary confined helper setup failure

**Context:**
D2 review R2: setup catch checks original failure instead of SocketCleanup merged primary, downgrading a later cleanup Error into Rejected.

**Needed:**
1. Reproduce ordinary setup rejection with fatal transfer or socket cleanup, then rethrow merged fatal identity after independent cleanup.

## Candidate verification

R2 `review-r2-red-001` reproduced the downgraded fatal rollback; the corrected regression passed in `review-owner-connected-green-static-003`, preserving the merged fatal identity.

Candidate gates in `b4-d2-implementation-001`: connected enforcer 268/268, platform 474/474 and profiler 32/32, with no skips or failures; static004 ratchet reported zero added warnings and no baseline growth. Rootful007 passed 8/8 and successor helper-regression-rootful001 passed 18/18; both postflight input manifests matched (1,829 checks each). Negative cases with injected effects are controlled tests, not proof of naturally occurring faults. Whole-B4 build002 was pending in the ledger; this issue remains open for controller settlement.

---
<!-- id: issue-20261105-213711-928-preserve-fatal-rollback-identity-after-ordinary-co  file: issue-20261105-213711-928-preserve-fatal-rollback-identity-after-ordinary-co.md -->
