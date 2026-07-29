---
title: "Implement Self-Healing Working Tree Sanitization on Successful Merge Paths in BranchRebaser"
severity: "HIGH"
status: "open"
priority: 8
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BranchRebaser.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Implement Self-Healing Working Tree Sanitization on Successful Merge Paths in BranchRebaser

**Context:**
The orchestrator's `BranchRebaser` is responsible for merging master into PR branches. If a merge fails, the rebaser enters a "rescue" path (`handleRescue`) where it checkouts only the allowed target files and discards all other modifications.

However, if the merge succeeds cleanly, the rebaser immediately compiles the branch and pushes it:
```kotlin
            return RebaseProcessState.VerifyAndPush(state.prNumber, state.branchName, state.worktreeDir, isRescue = false)
```
There is NO step in the clean-merge path to ensure that the working tree remains sanitized. If Jules' session has accidentally modified, created, or staged files outside of the declared `target_files` (such as modifications to resolved backlog markdown files or scratch artifacts) prior to the merge, those "dirty" modifications are never cleaned up or reverted. They persist in the PR and are pushed back to the repository, causing file noise and violating our sanitization invariants.

**Needed:**
1. In `BranchRebaser.kt`'s clean-merge path (inside `handleAttemptMerge` or as a pre-verification step in `handleVerifyAndPush`), implement an automated self-healing working tree sanitization check.
2. Retrieve all files modified/added between the merge-base with master and HEAD (or `origin/master...HEAD`).
3. For each modified file, verify that it is either:
   - Explicitly listed in the task's `target_files` (performing a robust component-based or ends-with match).
   - Located under the backlog directory (starts with `docs/internals/backlog/`).
4. If any file has been modified that does not meet these criteria, automatically self-heal the working tree:
   - Revert the extraneous modified file via `git checkout origin/master -- <file>`.
   - Automatically delete any extraneous untracked file.
   - Amend the merge commit (`git commit --amend --no-edit`) to exclude the discarded files, keeping the PR branch diff completely pristine and scoped to exactly `target_files` and backlog additions.
5. Write corresponding unit tests in `BranchRebaserTest.kt` verifying that the rebaser successfully identifies and purges unauthorized file edits on clean merge paths.
