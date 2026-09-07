---
title: "Add USER_NOTIF session evaluate machine around SupervisorSessionHandler"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - "issue-20260826-102722"
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorNotificationMachine.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandlerTest.kt"
target_symbols:
  - "SupervisorSessionHandler"
verify_cheap:
  - "./gradlew :enforcer:test --tests io.mazewall.enforcer.supervisor.SupervisorSessionHandlerTest"
needs_kernel: false
core_lock: false
effort: "large"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "d6b4419a-0742-496f-b182-b6f7560f33c9"
paperclip_identifier: "MAZ-1180"
---

# 🟡 [Severity: MEDIUM]: Add USER_NOTIF session evaluate machine around SupervisorSessionHandler

**Context:**
`SupervisorNotificationMachine.evaluateFastPath`/`evaluateJvm` is a route decision matrix, not a session lifecycle machine. `SupervisorSessionHandler.processNotification` is still extract/send/poll/read with ACK obligations. `issue-20260826-102722` splits the handler along `SupervisorRoute`; this issue adds `evaluate(state, event)` for the session around that split. Every path must still CONTINUE, KILL_THREAD, or ABORT exactly once.

**Needed:**
1. After the route split, add a session sealed state + events (notification received, path resolved, JVM verdict, fd injected, ack sent, failed).
2. `evaluate` returns effects for poll/read/send/inject; the handler becomes the interpreter.
3. Unit-test the session matrix without UNIX sockets. Keep the exactly-one-response invariant.
4. Run `./gradlew :enforcer:test --tests io.mazewall.enforcer.supervisor.SupervisorSessionHandlerTest`.

## Side effects
- Handshake poll read and reply become effects of a session evaluate function

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

**Resolution evidence (2026-09-07):** `SupervisorSessionMachine` has sealed session state,
events, effects, and a pure evaluator. `SupervisorSessionHandler` interprets the effects and
retains exactly-one terminal response handling. `./gradlew :enforcer:test --tests
io.mazewall.enforcer.supervisor.SupervisorSessionHandlerTest --tests
io.mazewall.enforcer.supervisor.SupervisorSessionMachineTest` passed.

<!-- id: issue-20260907-081005  file: issue-20260907-081005-add-user-notif-session-evaluate-machine-around-supervisorses.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
