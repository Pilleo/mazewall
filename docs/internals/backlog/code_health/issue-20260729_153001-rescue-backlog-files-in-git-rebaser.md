---
title: "Rescue Modified Backlog Markdown Files During Automated Git Branch Rescue to Prevent Progress Loss"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BranchRebaser.kt"
effort: "medium"
autonomy: "supervised"
---

# 🔴 [Severity: HIGH]: Rescue Modified Backlog Markdown Files During Automated Git Branch Rescue to Prevent Progress Loss

**Context:**
The Autonomous Backlog Orchestrator uses `BranchRebaser` to automate local merging and rebasing of target branches. When standard `git merge` fails or encounters unrelated histories, the orchestrator triggers the `HandleRescue` surgical branch recovery flow.

In `BranchRebaser.kt`'s `HandleRescue` implementation, the rebaser resets the worktree to `origin/master` and then checkouts ONLY the files explicitly declared in the issue's `target_files` list:
```kotlin
val intendedFiles = state.targetFiles
...
executeInDirNoRetry(state.worktreeDir, arrayOf("git", "reset", "--hard", "origin/master"))

val checkoutErrors = mutableListOf<String>()
for (file in intendedFiles) {
    try {
        executeInDirNoRetry(state.worktreeDir, arrayOf("git", "checkout", "origin/${state.branchName}", "--", file))
    } catch (e: Exception) {
        checkoutErrors.add(file)
    }
}
```

**The Problem:**
Jules updates the backlog markdown file's frontmatter status (such as moving it to `in_progress` or `resolved`). However, the backlog markdown file itself is almost never listed in `target_files` (as it's a documentation/issue tracking file, not a code implementation file).
As a result, when the rebaser executes the `HandleRescue` workflow, it checkouts the implementation code files but **silently discards** any backlog markdown changes made by Jules on the branch. The backlog file reverts back to its status on `origin/master` (which is typically `open`), causing silent progress and state loss.

**Needed:**
1. Update `BranchRebaser.kt`'s `HandleRescue` state to also identify and extract any modified backlog files.
2. Specifically, before resetting the worktree hard to `origin/master`, query the PR branch (`origin/${state.branchName}`) for any files under `docs/internals/backlog/` that have diverged or been modified compared to `origin/master`.
3. Combine those modified backlog file paths with the `intendedFiles` list so that they are also checked out from the Jules PR branch and preserved in the final rescue commit.
4. Ensure appropriate unit and integration tests inside `BranchRebaserTest.kt` are added to assert that backlog file modifications are correctly preserved during rescue scenarios.
