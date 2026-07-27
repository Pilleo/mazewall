---
title: "Replace git rebase with Merge-Base Diff Apply in Orchestrator's rebaseBranch()"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubClient.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/StateHandlerTest.kt"
  - "scripts/rebase_pr.sh"
effort: "medium"
autonomy: "supervised"
---

# 🔴 [Severity: HIGH]: Replace git rebase with Merge-Base Diff Apply in Orchestrator's rebaseBranch()

**Context:**
Jules operates in an isolated cloud workspace frozen at the moment the task session starts. If master progresses (new files added, existing files modified) **after** Jules diverged, Jules's cloud workspace remains on the older state.

When CI fails and Orchestrator comments the failure log to Jules, Jules applies a fix in its stale cloud workspace and **force-pushes** to GitHub. This force-push can:

1. **Overwrite the merge-commit** that the operator (or Orchestrator) previously made to bring the PR up to date.
2. **Appear to "delete" master-only files** (e.g. `HttpTransport.kt`) in the final `git diff origin/master..jules-branch` view — even though Jules never explicitly deleted those files. They simply didn't exist in Jules's frozen starting point.

**Root cause of accidental deletions:**
`git rebase origin/master` replays each Jules commit onto master. A Jules commit produced from a stale workspace (missing files that master later added) causes those files to appear as deleted from the final PR branch perspective.

**Proof (PR #367):**
```bash
# Merge-base between master and PR branch
$ git merge-base origin/master origin/fix-jvm-floor-thread-leak-...
3e1588da...

# Net diff of what Jules actually changed relative to HIS starting point:
$ git diff --name-status 3e1588da origin/fix-jvm-floor-thread-leak-...
M  enforcer/src/main/kotlin/io/mazewall/enforcer/JvmFloorWorkload.kt
A  enforcer/src/test/kotlin/io/mazewall/enforcer/JvmFloorWorkloadTest.kt
# HttpTransport.kt does NOT appear — Jules never had it, so it's not in Jules's net diff!
```

The correct diff captures exactly what Jules intended. `git rebase` was wrong because it replayed commit `41820130` which contained Jules's full stale workspace snapshot.

**The 100% Reliable Fix — Merge-Base Diff Apply:**
Instead of `git rebase origin/master`, use:
```bash
BASE=$(git merge-base origin/master origin/<jules-branch>)
git checkout -b clean origin/master
git diff $BASE origin/<jules-branch> | git apply --3way
git push --force-with-lease origin HEAD:<jules-branch>
```

**Why this is 100% reliable:**
- `diff(merge-base, jules-branch)` = exactly Jules's net intent
- Files master added *after* Jules diverged never appear in this diff — they're simply untouched
- True conflicts (Jules modified a file, master also modified it) surface as explicit 3-way merge conflicts rather than silent data loss
- DELETE/MODIFY conflicts (Jules deleted, master kept) auto-resolve by preferring master's version

**Needed:**
1. **Update `rebaseBranch()` in `GitHubCli.kt`:**
   - Implement `rebaseBranch(prNumber: String): Boolean` using the merge-base diff apply strategy instead of `gh pr update`/shell rebase.
   - Detect and report conflict counts as structured return data.
2. **Add `clearPrCache(prNumber: String)` to `GitHubClient` interface:**
   - When Orchestrator detects `currentSha != slot.lastHeadSha`, clear the cached `getPrMergeStatus` before calling `handleRebaseAndConflicts`.
3. **Update `scripts/rebase_pr.sh`** (already done):
   - Operator script already uses merge-base diff apply strategy.
4. **Unit Tests in `StateHandlerTest.kt`:**
   - Test that a PR branch that appears to delete master files (but the merge-base diff doesn't) gets correctly reconstructed without those deletions.
