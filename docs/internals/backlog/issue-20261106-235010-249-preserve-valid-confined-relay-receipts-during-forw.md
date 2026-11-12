---
title: "Preserve valid confined relay receipts during forwarding completion"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperControl.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/ConfinedHelperRelay.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperControlTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperRelayTest.kt"
target_symbols:
  - "ConfinedHelperRelay"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve valid confined relay receipts during forwarding completion

**Context:**
A real socketpair control regression rejects a valid matching Received after result descriptor delivery when Forwarded completes during control poll; retained 028 EOF and generic warning are consistent but not correlated.

Focused host evidence is in `.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b4-d2-implementation-001/relay-ordering-repro030/`: the intentionally RED test delivered a real observed descriptor, held the job acknowledgment, queued the matching receipt inside control poll, and completed forwarding before poll returned. The machine rejected the receipt in the old phase.

The relay now applies completed stages before a parent frame and retains at most one matching early receipt until forwarding completes. The original state-machine validation, deadlines and descriptor ownership remain unchanged. Independent scoped security review approved the patch (`relay-ordering-security-review033.md`). Host verification passed 62 cases, including pending forwarding, invalid/duplicate receipts, cancellation, expiry and an independent forwarding IOException with no helper receipt or parent DONE. The contained readiness class passed all eight cases freshly with no failures or skips (`relay-readiness-verification035/report.md`). Evidence paths are relative to the same B4 D2 evidence directory. These results do not establish the exact cause of gate 028; full-build verification remains pending.

**Needed:**
1. Order a valid early receipt after forwarding completion without weakening phase, identity, deadline, failure, or descriptor ownership checks; verify the focused regression and related tests.

---
<!-- id: issue-20261106-235010-249-preserve-valid-confined-relay-receipts-during-forw  file: issue-20261106-235010-249-preserve-valid-confined-relay-receipts-during-forw.md -->
