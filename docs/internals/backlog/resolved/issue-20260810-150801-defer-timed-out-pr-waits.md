---
title: "Defer Timed-Out PR Waits"
severity: "MEDIUM"
status: "resolved"
priority: 8
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogParser.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟡 [Severity: MEDIUM]: Defer Timed-Out PR Waits

**Context:** An `AWAITING_PR` timeout removed its active slot while leaving the backlog issue `in_progress`. The next daemon iteration identified the issue as orphaned and immediately resumed it, defeating the timeout.

**Needed:** Persist the timed-out issue as `deferred` before removing its slot, retain the skipped-task marker for the current scheduling pass, and cover the complete transition with an automated state-handler test.

**Resolution:** The timeout path now fails if its backlog issue cannot be found, persists `status: "deferred"`, records the issue in `skippedIds`, and only then removes the active slot.
