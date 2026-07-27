---
title: "Replace git rebase with Surgical Intended-Files Apply in Orchestrator's rebaseBranch()"
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
github_issue:
---

# 🔴 [Severity: HIGH]: Replace git rebase with Surgical Intended-Files Apply in Orchestrator's rebaseBranch()

**Context:**
Jules operates in an isolated cloud workspace frozen at the moment the task session starts. If master progresses (new files added, existing files modified, files moved) **after** Jules diverged, Jules's cloud workspace remains on the older state.

When CI fails and Orchestrator comments the failure log to Jules, Jules applies a fix in its stale cloud workspace and **force-pushes** to GitHub. Standard `git rebase` or full-tree patch apply replays each commit, causing master files added after divergence to appear as deleted from the final PR branch perspective.

Furthermore, `git merge-base` or full-tree patch restoration loops (`git checkout origin/master -- docs/`) can create untracked file additions when files move on master.

**The 100% Deterministic Fix — Surgical Intended-Files Extraction:**

1. **Compute `INITIAL_BASE` (Jules's original divergence point before any rebase commits):**
   ```bash
   FIRST_COMMIT=$(git rev-list --reverse origin/master..origin/$BRANCH | head -n 1)
   INITIAL_BASE=$(git rev-parse "${FIRST_COMMIT}~1" 2>/dev/null || git merge-base origin/master origin/$BRANCH)
   ```
2. **Extract `INTENDED_FILES` (Files Added or Modified by Jules for the task):**
   ```bash
   INTENDED_FILES=$(git diff --name-only --diff-filter=AM "$INITIAL_BASE" "origin/$BRANCH")
   ```
3. **Checkout and Stage ONLY Intended Task Files onto Fresh Master:**
   ```bash
   git checkout -B clean-pr origin/master
   for f in $INTENDED_FILES; do
     git checkout "origin/$BRANCH" -- "$f"
     git add "$f"
   done
   ```

**Why this is 100% reliable:**
- `diff-filter=AM` extracts **ONLY the files Jules Added or Modified** for the task, completely ignoring accidental deletions of master files.
- Operates on a clean `origin/master` worktree — zero chance of file pollution, corrupt patches, or missing file checkout errors.
- Guarantees the PR diff on GitHub contains **EXACTLY the task files** and passes compilation checks.

**Needed:**
1. **Update `rebaseBranch()` in `GitHubCli.kt`:**
   - Implement `rebaseBranch(prNumber: String): Boolean` using the surgical `INTENDED_FILES` extraction algorithm.
2. **Add `clearPrCache(prNumber: String)` to `GitHubClient` interface:**
   - When Orchestrator detects `currentSha != slot.lastHeadSha`, clear the cached `getPrMergeStatus` before calling `handleRebaseAndConflicts`.
3. **Update `../../../../scripts/rebase_pr.sh`** (done):
   - Operator script uses surgical intended-files extraction algorithm.
4. **Unit Tests in `StateHandlerTest.kt`:**
   - Add unit tests verifying that PR branches with stale workspace deletions are correctly reconstructed containing only intended task files.
