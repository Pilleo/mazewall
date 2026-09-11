---
title: "Bound USER_NOTIF reactor work and terminal reply ownership"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: ["platform"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/NotificationWork.kt"
target_symbols:
  - "NotificationWork"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bound USER_NOTIF reactor work and terminal reply ownership

**Context:**
RH-05 through RH-08: 30-second deadlines, bounded retries, late-completion disposal and control IPC require recorded regression evidence.

**Needed:**
1. Record implementing commits and executable regression evidence in the canonical hardening manifest.

**Resolution:** RH-05–RH-08 are implemented by commits e7b832d5. Regression owners: NotificationDeadlineTest, ControlSocketExchangeTest and UserNotifSharedTest.

**Verification:** `./gradlew :platform:test :platform:check` and supervisor/profiler adapter regressions passed in the deadline package. Timeout, late completion, ENOENT, hard failure and bounded EINTR have direct assertions.

The [canonical finding manifest](../../designs/core/post-review-hardening-evidence.md#liveness-and-boundary-review-rh) maps individual methods and gates. [Final integration evidence](../../designs/core/review-hardening-verification.md) distinguishes fresh execution from cached outputs and does not imply aggregate acceptance from this scoped resolution.

---
<!-- id: issue-20260910-225751-953-bound-user-notif-reactor-work-and-terminal-reply-o  file: issue-20260910-225751-953-bound-user-notif-reactor-work-and-terminal-reply-o.md -->
