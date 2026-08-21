---
title: Enforce Non-Empty Target Modules in Backlog Validator and Safe Empty Scheduler
  Fallback
severity: MEDIUM
status: open
priority: high
dependencies: []
component: orchestrator
target_modules:
- :tools:orchestrator
target_files:
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogValidator.kt
effort: small
autonomy: autonomous
github_issue: 351
---

# 🔶 [Severity: MEDIUM]: Enforce Non-Empty Target Modules in Backlog Validator and Safe Empty Scheduler Fallback

**Context:**
The multi-task orchestrator scheduler relies on `target_modules` and `target_files` frontmatter lists to execute non-conflicting tasks concurrently:
```kotlin
// Filter for conflict-free: target_files(B) ∩ target_files(active) = ∅ AND target_modules(B) ∩ target_modules(active) = ∅
val conflictFreeIssues = unblockedIssues.filter { issue ->
    issue.targetFiles.none { it in activeFiles } &&
    issue.targetModules.none { it in activeModules }
}
```
Currently, `BacklogValidator` only checks if the keys `target_modules` and `target_files` exist in the markdown file string. It does not verify if they are empty lists (e.g. `target_modules: []` or `target_files: []`).
If a backlog task is defined with `target_modules: []`, it has no conflict constraints. The scheduler will eagerly run it in parallel with other tasks. However, in reality, a task without declared target modules can edit any files, leading to Git merge conflicts, concurrent build failures, and state race conditions.

**Needed:**
Introduce strict target declarations in validation and fallback scheduling rules:
1. Update `BacklogValidator` to assert that `target_modules` is present and contains at least one valid Gradle module (e.g., `":enforcer"` or `":profiler"`). Active tasks cannot have empty target modules.
2. Update the scheduler (`selectAndStartTasks`) to handle any empty `target_modules` or `target_files` conservatively (e.g., treating empty targets as a conflict with all active tasks, meaning they can only be run sequentially / exclusively).
