---
title: "Replace rebaseBranch() with git merge: Use merge to keep Jules PR branches up to date"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubClient.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/StateHandlerTest.kt"
effort: "small"
autonomy: "supervised"
---

# 🔴 [Severity: HIGH]: Replace rebaseBranch() with git merge

## Context

When a Jules PR is behind `origin/master`, the Orchestrator currently calls `GitHubCli.rebaseBranch()` which
applies a "surgical worktree rebase" — trying to identify which files Jules intended to change and cherry-picking
only those onto fresh master. This approach is fundamentally broken.

### Why rebase Destroys the PR

The correct PR diff (what should land in master) is:

```
diff(master, Jules_branch) after Jules's commits are on top of master
```

Standard git gives you this cleanly with a **merge commit**:

```
git merge-base(master, merged_branch) = master_head   # merge commit has master as parent
git diff master..merged_branch = Jules's commits only  # master files are NOT in the diff
```

**Proof with PR #367** (Jules changed only 2 files):
- `diff(merge-base, branch)` = **2 files** ✅ (Jules's actual changes)
- `diff(master, branch)` = **25 files** ❌ (branch is behind — master moved ahead)
- After `git merge origin/master` into branch:
  - `diff(master, merged-branch)` = **2 files** ✅ (merge-base becomes master tip)

The merge makes the PR diff correct automatically, **with zero file-filtering logic**.

### Why the Current rebaseBranch() Fails

The current approach tries to reconstruct "which files Jules intended" from git history, then cherry-picks only those
onto fresh master. This fails because:

1. **After the first rebase, Jules's commit history is destroyed** — all history collapses into one commit authored
   by the Orchestrator. Subsequent "behind master" events have no way to find Jules's original divergence point.

2. **File-filtering heuristics are unreliable** — `diff-filter=AM` from `FIRST_COMMIT~1` picks up master's own
   new files when the branch has been previously rebased (observed: 112 files instead of 13 for PR #375).

3. **Rebase rewrites history** — Jules signed the original commits. Our rebase replaces them with an Orchestrator
   commit, breaking the commit attribution chain.

## The Correct Solution: `git merge origin/master`

When a PR branch is behind master, simply **merge master into the branch**:

```bash
# In a temporary worktree for isolation:
git worktree add $WORKTREE_DIR "origin/$BRANCH" --detach
cd $WORKTREE_DIR
git merge origin/master --no-edit -m "chore: merge master into PR #$PR_NUMBER to keep up to date"
git push --force-with-lease origin "HEAD:$BRANCH"
```

This:
- **Preserves Jules's commits and authorship** — they remain signed by `google-labs-jules[bot]`
- **Makes the PR diff correct** — `diff(master, merged_branch)` = exactly Jules's session changes
- **Is idempotent** — can be run multiple times as master continues to advance
- **Handles conflicts correctly** — `git merge` fails on genuine conflicts, signalling the need for human review
- **Requires zero file-filtering logic** — standard git merge semantics do the right thing

## Required Implementation

### 1. Rename and simplify `rebaseBranch()` → `mergeMasterIntoBranch()`

**File:** `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubClient.kt`

Rename the method and update its contract:

```kotlin
/**
 * Merges the current origin/master into the given PR branch using an isolated
 * worktree. Preserves Jules's original commits and authorship. The resulting
 * PR diff will contain exactly Jules's session changes relative to master.
 *
 * Returns [RebaseResult.success = true] if the merge succeeded and was pushed.
 * Returns [RebaseResult.success = false] if there are merge conflicts (human intervention required).
 */
fun mergeMasterIntoBranch(prNumber: String): RebaseResult
```

> Keep `rebaseBranch` as a deprecated alias calling `mergeMasterIntoBranch` during the transition,
> or rename in one pass across all callers.

### 2. Implement in `GitHubCli`

**File:** `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt`

Replace the entire `rebaseBranch()` implementation with:

```kotlin
override fun mergeMasterIntoBranch(prNumber: String): RebaseResult {
    clearPrCache(prNumber)
    val worktreeDir = File("../temp-merge-$prNumber")
    try {
        val branchName = execute("gh", "pr", "view", prNumber, "--json", "headRefName", "--jq", ".headRefName").trim()
        if (branchName.isBlank()) return RebaseResult(success = false, conflictCount = 0)

        execute("git", "fetch", "origin", "master")
        execute("git", "fetch", "origin", branchName)

        // Clean up any previous worktree
        executeWithoutRetry("git", "worktree", "remove", worktreeDir.absolutePath, "--force")
        worktreeDir.deleteRecursively()
        executeWithoutRetry("git", "worktree", "prune")

        // Create worktree on the PR branch (not master — we're merging INTO the branch)
        execute("git", "worktree", "add", worktreeDir.absolutePath, "origin/$branchName", "--detach")

        // Merge master into the branch. Conflicts = failure, signal human intervention.
        try {
            executeInDir(
                worktreeDir, "git", "merge", "origin/master",
                "--no-edit",
                "-m", "chore: merge master into PR #$prNumber to keep up to date"
            )
        } catch (e: Exception) {
            // Merge conflict — abort and signal
            executeInDir(worktreeDir, "git", "merge", "--abort")
            System.err.println("Merge conflict on PR #$prNumber: ${e.message}")
            return RebaseResult(success = false, conflictCount = 1)
        }

        // Check if anything actually changed (branch might already be up to date)
        val aheadOfMaster = executeInDir(
            worktreeDir, "git", "rev-list", "--count", "origin/master..HEAD"
        ).trim().toIntOrNull() ?: 0

        if (aheadOfMaster == 0) {
            // Nothing to merge — already up to date (shouldn't happen if caller checks, but safe)
            System.err.println("Nothing to merge for PR #$prNumber — already up to date")
            return RebaseResult(success = true, conflictCount = 0)
        }

        executeInDir(worktreeDir, "git", "push", "--force-with-lease", "origin", "HEAD:$branchName")
        return RebaseResult(success = true, conflictCount = 0)

    } catch (e: Exception) {
        System.err.println("Merge failed for PR #$prNumber: ${e.message}")
        return RebaseResult(success = false, conflictCount = 0)
    } finally {
        executeWithoutRetry("git", "worktree", "remove", worktreeDir.absolutePath, "--force")
        worktreeDir.deleteRecursively()
        executeWithoutRetry("git", "worktree", "prune")
    }
}
```

### 3. Update call site in `OrchestratorStates`

**File:** `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt` (line ~741)

```kotlin
// Before:
val rebaseResult = env.gitHubClient.rebaseBranch(prNumber)

// After:
val rebaseResult = env.gitHubClient.mergeMasterIntoBranch(prNumber)
```

### 4. Update tests

**File:** `tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/StateHandlerTest.kt`

- Rename all `rebaseBranch` mock references to `mergeMasterIntoBranch`
- Verify the test mock now stubs `mergeMasterIntoBranch` and the state machine calls it correctly

## Verification

- Unit test: `StateHandlerTest` — mock `mergeMasterIntoBranch`, verify it's called when `isBehind || isConflicting`
- Unit test: `GitHubCliTest` — with a git repo fixture, verify merge produces correct PR diff
- Integration test: Run against real PR that is behind master, verify Jules's commits are preserved after merge

## Notes on Conflict Handling

`git merge` fails with exit code 1 on conflicts. The `executeInDir` method (which uses `RetryUtils`) should NOT
retry on this failure — conflict is not a transient error. Consider adding `git merge` to the no-retry list, or
catching the exception and aborting the merge before retrying.

`executeWithoutRetry` should be used for the merge command, or the catch-and-abort pattern above is sufficient.

## What Happens to PRs Already Corrupted by rebaseBranch()

PRs where `rebaseBranch()` already ran (e.g., PR #375) have Jules's original commits destroyed. Those branches
now have a single Orchestrator-authored commit. For those PRs, the Jules API approach
(see git history) remains the only way to recover the correct file set. New PRs touched only by `mergeMasterIntoBranch`
will work correctly from the start.

If possible, also refactor rebase_pr.sh script
