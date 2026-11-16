---
title: "Preserve queued confined helper final frames across peer hangup"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/ffi/networking/ConfinedHelperChannel.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ConfinedHelperControlTest.kt"
target_symbols:
  - "ConfinedHelperChannel"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve queued confined helper final frames across peer hangup

**Context:**
D2 real-socket control tests reproduce POLLIN plus POLLHUP rejecting an already queued RETIRED frame after peer close. The bounded receiver must consume readable final bytes without accepting errors or extending deadlines. RED evidence: b4-d2-implementation-001/control-reconnect-hangup-red-001.

**Needed:**
1. Distinguish readable hangup from terminal poll errors and verify the real socketpair final-frame plus following EOF regression.

## Verification in progress

The actual socketpair regression `complete final frame remains readable after peer
shutdown` failed before the change. The bounded channel now permits readable input
on hangup while preserving poll errors, write failures, ancillary validation and its
original deadline. Control/Relay/Channel selection passed50/50 in
`b4-d2-implementation-001/control-reconnect-green-static-001`; connected daemon/
cleanup selection later passed77/77 enforcer and24/24 platform cases. Final D2
qualification and the combined whole-B4 gate remain pending; keep this issue open.

The connected228-case D2 host selection also passed; rootful006 completed all eight
actual readiness/retirement cases with no skips/aborts/failures. Independent review
and final whole-B4 acceptance remain pending.

## Candidate verification

The queued-final-frame socketpair RED and `control-reconnect-green-static-001` GREEN remain the focused evidence; the latter passed 50/50 cases.

Candidate gates in `b4-d2-implementation-001`: connected enforcer 268/268, platform 474/474 and profiler 32/32, with no skips or failures; static004 ratchet reported zero added warnings and no baseline growth. Rootful007 passed 8/8 and successor helper-regression-rootful001 passed 18/18; both postflight input manifests matched (1,829 checks each). Negative cases with injected effects are controlled tests, not proof of naturally occurring faults. Whole-B4 build002 was pending in the ledger; this issue remains open for controller settlement.

---
<!-- id: issue-20261105-191554-457-preserve-queued-confined-helper-final-frames-acros  file: issue-20261105-191554-457-preserve-queued-confined-helper-final-frames-acros.md -->
