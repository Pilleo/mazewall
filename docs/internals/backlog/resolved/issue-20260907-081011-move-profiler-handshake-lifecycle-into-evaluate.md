---
title: "Move profiler handshake lifecycle into evaluate"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - "issue-20260907-080818"
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/HandshakeSession.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/ProfilerTraceListener.kt"
target_symbols:
  - "ProfilerSessionHandler"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "e137dd73-0c29-4c2f-ad0f-cfb60db9a3b7"
paperclip_identifier: "MAZ-1181"
---

# 🟡 [Severity: MEDIUM]: Move profiler handshake lifecycle into evaluate

**Context:**
Profiler session and parent trace listener assign lifecycle during socket and notification I/O: `ActiveSession` on `IOException` without `evaluate`, a noise-path `CONTINUE` that skips the machine, handshake poll/read that may `onShutdown` mid-I/O, and `ProfilerTraceListener` stepping `Disconnected`/`AwaitingEvent` on a `DataInputStream`. `ProfilerSessionMachine` exists; the I/O paths do not go through it.

**Needed:**
1. Route handshake, notification, and parent-listener I/O through `evaluate(state, event)` plus effects. No `state = ActiveSession` / `Disconnected` inside catch blocks or read loops.
2. Noise-path CONTINUE must be an explicit event/effect, not a skip around the machine (ACK semantics are `issue-20260907-081107`).
3. Unit-test the matrix without sockets.
4. Run focused profiler engine tests.

## Side effects
- ActiveSession and Disconnected are assigned only from evaluate, not during socket IO

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

**Resolution evidence (2026-09-07):** `ProfilerSessionMachine` and `TraceListenerMachine`
own lifecycle transitions. Handshake, notification, failure, parent-listener, and noise-path
CONTINUE handling all feed explicit events through their evaluators. `./gradlew :profiler:test
--tests io.mazewall.profiler.engine.ProfilerSessionMachineTest --tests
io.mazewall.profiler.engine.ProfilerSessionHandlerTest` passed.

<!-- id: issue-20260907-081011  file: issue-20260907-081011-move-profiler-handshake-lifecycle-into-evaluate.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
