---
title: "Preserve typed ADDFD notification-gone and native-error outcomes"
severity: "LOW"
status: "open"
priority: low
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorReplyInterpreter.kt"
target_symbols:
  - "SupervisorReplyInterpreter"
open_questions: false
---

# 🟡 [Severity: LOW]: Preserve typed ADDFD notification-gone and native-error outcomes

**Context:**
Repository-wide review, 2026-11-04: [SUP-06](../reviews/2026-11-04-repository-review-findings.md#sup-06). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P3 · source-confirmed · high confidence · terminal outcome diagnostics.**

**Trigger:** the task exits after the final validity check but before ADDFD-with-SEND, causing ENOENT, or ADDFD returns another native errno.

**Evidence:** `enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/SupervisorReplyInterpreter.kt:126–134,174–183` wraps every ADDFD failure in `ReplyFailed(cause)` without its typed outcome. `SupervisorReplyEffect.kt:63–70` defaults that outcome to `Failed(EIO)`. `TraceeAuthorityNotificationWork.kt:95–99` returns it unchanged to the shared reactor. In contrast, ordinary SEND preserves `NotificationGone` and native errno at `SupervisorReplyInterpreter.kt:155–160`. Linux v6.12 `kernel/seccomp.c:1717–1720` explicitly returns ENOENT when the notification no longer exists.

**Consequence:** terminal handling remains fail-closed and no second reply is enabled, but the documented gone counter and structured native failure evidence are wrong for descriptor-producing operations. The textual exception alone is not equivalent to the promised typed outcome.

**Smallest correction/check:** classify ADDFD ENOENT as `UserNotifReplyResult.NotificationGone`; retain other errno values and the ADDFD operation in `Failed`. Fault-inject ENOENT/EACCES/EINTR exhaustion and assert exact outcome, one descriptor disposal, and no fallback SEND.

**Mapping/dependency:** **NEW narrow residual** against the typed-outcome contract at `docs/internals/designs/core/notification-liveness-contract.md:17–24,33–39`; related resolved `docs/internals/backlog/resolved/issue-20260910-225751-953-bound-user-notif-reactor-work-and-terminal-reply-o.md`. No prerequisite for the correctness fixes above; include with reply diagnostics/Plan B6 rather than calling it a security bypass.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235359-647-preserve-typed-addfd-notification-gone-and-native-  file: issue-20261103-235359-647-preserve-typed-addfd-notification-gone-and-native-.md -->
