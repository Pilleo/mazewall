---
title: "Preserve atomic FD injection notification-gone and errno outcomes"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - "enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorReplyInterpreter.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorReplyInterpreterMatrixTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/TraceeAuthorityReplyContractTest.kt"
target_symbols:
  - "injectReply"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve atomic FD injection notification-gone and errno outcomes

**Context:**
B6 connected-source inventory confirmed that ADDFD failures omit ReplyFailed.outcome and become Failed(EIO), including ENOENT; source FD disposal and one-terminal semantics remain intact.

**Needed:**
1. Add actual interpreter and authority-work outcome regressions, then preserve NotificationGone and original errno at the shared injection boundary without another reply.
2. Cover ENOENT, a distinct ordinary errno and exhausted EINTR with a success control. Assert source FD close exactly once, exact typed Ready.reply outcome and no fallback SEND/second terminal operation.

## Source evidence

- [SupervisorReplyInterpreter.kt](../../../enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorReplyInterpreter.kt):131–134 omits the outcome for FdInjectionResult.Failed.
- [SupervisorReplyEffect.kt](../../../enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorReplyEffect.kt):66–70 defaults ReplyFailed to Failed(EIO); [TraceeAuthorityNotificationWork.kt](../../../enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/TraceeAuthorityNotificationWork.kt):95–99 forwards it.
- [UserNotifReply.kt](../../../platform/src/main/kotlin/io/mazewall/platform/seccomp/UserNotifReply.kt):90–94 already distinguishes NotificationGone from Failed(actual errno) for ordinary SEND.
- Existing matrix114–127 checks failure type, atomic operation and close, but not the typed errno/gone result. Exception text still preserves errno; no FD leak, second reply or authorization bypass is established.

Confirmed independently during B6 source inventory; intake is preserved at `.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b6-source-findings-intake-001.md`. Filed after G4 preservation/authentication. Product repair and executed regressions remain pending.

---
<!-- id: issue-20261105-070253-578-preserve-atomic-fd-injection-notification-gone-and  file: issue-20261105-070253-578-preserve-atomic-fd-injection-notification-gone-and.md -->
