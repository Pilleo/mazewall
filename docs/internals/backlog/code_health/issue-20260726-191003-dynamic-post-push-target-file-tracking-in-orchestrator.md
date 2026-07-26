---
title: Dynamic Post-Push Target File Tracking in Orchestrator Active Slots
severity: HIGH
status: open
priority: 10
dependencies:
- issue-20260726-191002
component: orchestrator
target_modules:
- :tools:orchestrator
target_files:
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt
effort: medium
autonomy: autonomous
---

# 🔴 [Severity: HIGH]: Dynamic Post-Push Target File Tracking in Orchestrator Active Slots

**Context:**
During execution, an agent may discover and modify files not originally declared in the backlog issue's initial `target_files` list. If a parallel agent is scheduled assuming the initial `target_files` list, diff collisions occur once both branches push.

**Needed:**
1. In `AWAITING_PR` state of `OrchestratorStates.kt`, query actual changed files on the branch (`git diff --name-only origin/master..headRefName`).
2. Populate `slot.actualTargetFiles` dynamically in `SlotContext`.
3. Update `DependencyGraph` task scheduling checks to inspect `slot.actualTargetFiles` across all in-flight active slots, dynamically pausing or sequencing pending tasks whose `target_files` intersect with active PR changes.
