---
title: "Move orchestrator I/O out of OrchestratorState execute"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20260907-080802"
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
target_symbols:
  - "OrchestratorDaemon"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "1948692b-2a44-4496-9e90-b04fb60909c8"
paperclip_identifier: "MAZ-1183"
---

# 🟡 [Severity: MEDIUM]: Move orchestrator I/O out of OrchestratorState execute

**Context:**
`OrchestratorState.evaluate` exists and returns `Transition(nextState, commands)`, but evaluate still reads `System.currentTimeMillis` and issue files, and `execute` performs GitHub/Telegram I/O then `slot.state = this`. `selectAndStartTasks` also assigns `PendingApprovalState` after parsing issues. The machine is not the single writer of slot state.

**Needed:**
1. Keep `evaluate` pure: no clock, filesystem, or GitHub. Clock and parsed issues arrive as events.
2. `execute` (or the daemon interpreter) only runs commands from the transition; it does not invent extra `slot.state =` assignments.
3. `selectAndStartTasks` must obtain `PendingApprovalState` from evaluate, not assign it after parsing.
4. Run `:tools:orchestrator:test` state-handler tests.

## Side effects
- execute returns commands; GitHub backlog and approval I/O run in the daemon interpreter

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-081051  file: issue-20260907-081051-move-orchestrator-i-o-out-of-orchestratorstate-execute.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
