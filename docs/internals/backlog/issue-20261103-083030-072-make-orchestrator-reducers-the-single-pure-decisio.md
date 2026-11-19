---
title: "Make orchestrator reducers the single pure decision path"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies:
  - "issue-20261103-083002-763-make-orchestrator-task-state-the-authoritative-per"
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorContext.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorEnvironment.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/StateTransitionTest.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/StateHandlerTest.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/OrchestratorDaemonRunnerTest.kt"
  - "tools/orchestrator/README.md"
target_symbols:
  - "OrchestratorState"
  - "OrchestratorEvent"
  - "Transition"
  - "CommandInterpreter"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make orchestrator reducers the single pure decision path

**Context:**
Design review finding 8 (2026-11-03): evaluate reads wall-clock time and approval-file contents, while execute independently decides CI failure feedback and retry bookkeeping. Reducer tests therefore do not fully describe production dispatch. CommandInterpreter returns Any? and approval transitions can carry a PENDING issue-number placeholder. This is a source-confirmed decision/effect separation gap, following the resolved command/event refactor.

**Needed:**
1. Use immutable state, typed events, captured time and settings as reducer inputs. Return the next state and explicit effects; interpret effects once and return typed completion/failure events. Remove duplicate execute decisions while preserving retry limits, approval behavior, SHA-based notification deduplication and serial merges. Add deterministic replay and fake-interpreter integration checks.

**Evidence and scope:**
- `OrchestratorStates.kt:503-578` reads time and approval-file contents inside `evaluate`; `:1160-1191` emits CI decisions that `execute` separately implements at `:1325-1366`.
- `OrchestratorDaemon.kt:15` returns `Any?` from command interpretation, while `OrchestratorStates.kt:542,699-700` can represent an issue awaiting Jules startup with the string `PENDING` as its issue number.
- Follow-up to the [resolved command/event refactor](resolved/issue-20260731-120101-orchestrator-command-event-refactor-for-testability.md), after the [authoritative persistence model](issue-20261103-083002-763-make-orchestrator-task-state-the-authoritative-per.md). Reuse the existing event/command machinery and CLI adapters.
- Capture time, file contents and external statuses at the I/O boundary. Required command results must arrive as typed success/failure events before advancing to a phase requiring those results. Keep replay checks local and deterministic; this task does not introduce an event store or claim exactly-once remote delivery across crashes.

**Acceptance and verification:**
- Repeated evaluation of identical state/event/time/settings inputs yields identical transitions and leaves its inputs unchanged, without clock, filesystem or network access.
- Drive the real interpreter with fake clients through approval, issue creation failure/success, CI failure deduplication and retry, review and merge. Assert the emitted effects are the production effects and custom timing settings are honored consistently.
- Preserve authentication/permission failure handling and operator approval semantics. Run `./gradlew :tools:orchestrator:test -PincludeOrchestrator=true`, then `./gradlew build -PincludeOrchestrator=true` at implementation completion.

---
<!-- id: issue-20261103-083030-072-make-orchestrator-reducers-the-single-pure-decisio  file: issue-20261103-083030-072-make-orchestrator-reducers-the-single-pure-decisio.md -->
