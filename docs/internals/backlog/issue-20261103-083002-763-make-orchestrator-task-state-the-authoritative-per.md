---
title: "Make orchestrator task state the authoritative persistence model"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorContext.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/OrchestratorContextFieldsTest.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/OrchestratorDaemonRunnerTest.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/StateHandlerTest.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/StateTransitionTest.kt"
  - "tools/orchestrator/README.md"
target_symbols:
  - "SlotContext"
  - "OrchestratorContext"
  - "OrchestratorState"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Make orchestrator task state the authoritative persistence model

**Context:**
Design review finding 7 (2026-11-03): task identities are duplicated across sealed OrchestratorState payloads, mutable SlotContext fields and legacy OrchestratorContext fields. A fully populated CiRunningState does not round-trip unless callers also populate the duplicate fields. fromContext fabricates dummy identifiers and unknown state names fall back to SelectTaskState. This is a source-confirmed construction and recovery-contract gap; no live daemon failure was reproduced.

**Needed:**
1. Make sealed task state the single source of required phase data; serialize that data directly using the existing Java Properties format, derive compatibility accessors, and decode malformed or unknown states into explicit recovery failure. Preserve supported legacy names and serial merge handling. Prove every state round-trips without manually synchronized fields.

**Evidence and scope:**
- `OrchestratorContext.kt:5-38,78-112` stores state and identity fields independently and serializes the duplicate fields. `OrchestratorStates.kt:152-202` manually synchronizes them through `updateSlot`.
- `OrchestratorStates.kt:303-426` has different recovery contracts: `fromSlot` requires selected fields, while `fromContext` substitutes `dummy-*` identities; unknown names become `SelectTaskState`.
- Follow-up to the [resolved type-state refactor](resolved/issue-20260729-131004-refactor-orchestrator-state-machine-compile-time-type-states.md). Keep its payload-bearing states; finish ownership and persistence rather than reopening the original conversion.
- Keep independent retry/monitoring data explicitly owned alongside task state. Preserve atomic state-file replacement, supported legacy migrations, approval requirements, and per-slot identity. Malformed recovery must retain evidence and prevent task effects for that slot.

**Acceptance and verification:**
- Construct every phase with only its required state payload, save/load it, and assert identical phase and identities without assigning mirrored context fields.
- Cover missing required fields, blank identifiers, unknown states, supported old state names, and multiple slots. Invalid input must neither fabricate identity nor silently become an idle task.
- Retain daemon restart, approval and serial-merge tests. Run `./gradlew :tools:orchestrator:test -PincludeOrchestrator=true`, then `./gradlew build -PincludeOrchestrator=true` at implementation completion.

---
<!-- id: issue-20261103-083002-763-make-orchestrator-task-state-the-authoritative-per  file: issue-20261103-083002-763-make-orchestrator-task-state-the-authoritative-per.md -->
