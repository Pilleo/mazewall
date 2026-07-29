---
title: "Automatically Resume and Re-import Orphaned In-Progress Backlog Tasks into Active Slots"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt"
effort: "small"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Automatically Resume and Re-import Orphaned In-Progress Backlog Tasks into Active Slots

**Context:**
Currently, when the Autonomous Backlog Orchestrator starts or when the active tasks context is reset/cleared (such as when `FORCE_TASK` is specified, or if `.orchestrator_state.properties` is wiped or deleted), the active tasks inside `context.activeSlots` are completely cleared.

However, the local backlog markdown files themselves under `docs/internals/backlog/` still retain their frontmatter `status: "in_progress"`.

The current task scheduler in `OrchestratorDaemonRunner.selectAndStartTasks()` only filters for issues with `status == "open"` when checking candidates:
```kotlin
val openIssues = candidateIssues.filter { it.status == "open" }
```
Because of this, any backlog task that has already transitioned to `in_progress` is completely ignored and bypassed by the scheduler. It will never be re-selected, meaning that its active PR and Jules session are completely orphaned and forgotten. This is precisely why PR #398 was not monitored, not updated with master, and not recognized as dirty or cleaned.

**Needed:**
1. In `OrchestratorDaemonRunner.selectAndStartTasks()`, before performing standard new task selection, scan `allIssues` to identify any tasks whose backlog file frontmatter indicates `status == "in_progress"` but which are NOT currently present in the active slots list (`context.activeSlots`).
2. For each such orphaned in-progress task found, automatically recreate its `SlotContext` and restore/resume its execution:
   - Instantiate a new `SlotContext(issue.id)`.
   - Populate its core properties from the parsed backlog issue (`currentIssueTitle`, `currentIssueFile`, `githubIssueNumber`).
   - Set its initial state to `PendingApprovalState(issue.id, issue.title, issue.file.path, issue.githubIssue?.toString())`. This state is designed to automatically detect that a `githubIssueNumber` is already associated with the task, log a "resuming already-in-progress task" message, and transition cleanly to `AwaitingJulesStartState` and then `AwaitingPrState` without requiring fresh user approvals or recreating the issue.
   - Add the reconstructed slot to `context.activeSlots` and save state.
3. Write a dedicated unit test in `OrchestratorDaemonRunnerTest.kt` ensuring that if an issue is parsed with `status = "in_progress"` and is missing from active slots, `selectAndStartTasks()` automatically resumes it by adding it back to active slots in `PendingApprovalState` with the proper issue metadata.
