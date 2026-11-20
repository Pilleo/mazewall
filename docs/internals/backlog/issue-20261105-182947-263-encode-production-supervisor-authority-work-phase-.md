---
title: "Encode production supervisor authority-work phase payloads in one closed state"
severity: "LOW"
status: "open"
priority: low
dependencies: []
component: "enforcer"
target_modules: [":enforcer"]
target_files:
   - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/supervisor/TraceeAuthorityNotificationWork.kt"
   - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/TraceeAuthorityReactorTest.kt"
   - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorNotificationCancellationTest.kt"
target_symbols:
  - "TraceeAuthorityNotificationWork"
open_questions: false
---

# 🟡 [Severity: LOW]: Encode production supervisor authority-work phase payloads in one closed state

**Context:**
Recursive state-design review B-01: the production poll interpreter derives snapshot/verdict/result/exec/reply ownership from correlated nullable fields and closed. Current reactor ordering maintains its invariant; no runtime bug or repoll defect is demonstrated. The existing pure SupervisorSessionMachine does not carry these production payload phases.

**Needed:**
1. Replace the redundant nullable phase representation with private payload-bearing alternatives and exhaustive dispatch, retaining the single reactor reply authority, native validation points, exception-safe ownership and physical helper retirement.

## Design opportunity and current invariant

[Recursive review B-01](../reviews/2026-11-05-state-complexity-review.md):
`TraceeAuthorityNotificationWork.kt:28–34,39–87` derives its phase from nullable
future/request/transport/handoff/exec fields and `closed`. `:66` dynamically
requires a future in every remaining live branch. After terminal offer, that
future is null while `closed` remains false; repoll would fail. **No production
repoll defect is established:** `SeccompSessionHandler.kt:218–260` consumes Ready
through its sole reply lease and closes the work, with deadline priority intact.

Replace the correlated fields, do not add a parallel enum. Private variants can
carry `AwaitingSnapshot`, `AwaitingVerdict(request, transport, result)`,
`AwaitingResult(request, result)`, `PreparingExec(work)`, `ReplyOffered(handoff)`
and `Closed`. Keep genuinely cross-phase session/job ownership lifetime-owned.
Use a small pure transition choice where useful; observed events must not hide
I/O in closures. Future polling/join, clocks, ID_VALID, IPC, native replies,
cancellation and resource disposal remain interpreter effects.

The benefit is fewer invalid private combinations and exhaustive close handling,
not a new owner or framework. Transfer payload ownership exception-safely. Reply
offer is not delivery; cancellation is not helper reap. Preserve protocol bytes,
immutable snapshots, all notification-ID validation points, failure identity and
secure-exec fail-closed behavior. No public API change is authorized.

## Acceptance and verification

- Bounded phase/event traces cover pending/ready/failed snapshot, fast/AskJvm
  routes, partial/malformed verdict, helper result/failure, ID_VALID rejection,
  exec/ordinary offer, close in every phase and late resource completion.
- Assert at-most-once decision/terminal offer and exactly-once resource
  disposition, no callback native reply, no post-close admission/IPC, and no
  capacity reuse before actual reap. Late cleanup remains required.
- Consume Ready through the actual reactor lease; preserve
  deadline-wins-over-ready and terminal reply claim behavior. Tests must drive
  the production interpreter, not only an independent model.
- Run focused supervisor/reactor/cancellation tests and existing fresh-JVM
  authority qualification, then the required `./gradlew build` gate. Update the
  closed-subclass architecture assertion if the new type falls within it.

## Existing work and scope

The [resolved session-machine implementation](resolved/issue-20260907-081005-add-user-notif-session-evaluate-machine-around-supervisorses.md)
and [resolved reactor reply/deadline owner](resolved/issue-20260910-225751-953-bound-user-notif-reactor-work-and-terminal-reply-o.md)
remain positive existing work. They do not carry this production interpreter's
payload phases. Do not sweep fixture work or shared pools into this low-priority
design task. Coordinate [helper-capacity work](issue-20261103-235358-819-retain-supervisor-helper-capacity-across-fatal-wor.md)
only if shared pool ownership is touched; no mandatory unrelated dependency.

---
<!-- id: issue-20261105-182947-263-encode-production-supervisor-authority-work-phase-  file: issue-20261105-182947-263-encode-production-supervisor-authority-work-phase-.md -->
