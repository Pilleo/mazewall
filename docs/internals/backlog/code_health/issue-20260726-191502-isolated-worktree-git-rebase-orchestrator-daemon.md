---
title: Isolate Orchestrator Git Operations from Main Working Directory using Temporary
  Worktrees
severity: HIGH
status: open
priority: 9
dependencies: []
component: orchestrator
target_modules:
- :tools:orchestrator
target_files:
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt
effort: medium
autonomy: autonomous
---

# 🔴 [Severity: HIGH]: Isolate Orchestrator Git Operations from Main Working Directory using Temporary Worktrees

**Context:**
When an automated rebase is needed during the CI monitoring loop, the orchestrator triggers the local git rebase fallback in `GitHubCli.kt`'s `rebaseBranch` method:
```kotlin
val branchName = execute("gh", "pr", "view", prNumber, "--json", "headRefName", "--jq", ".headRefName").trim()
if (branchName.isBlank()) return false

execute("git", "fetch", "origin", branchName)
execute("git", "fetch", "origin", "master")

val currentBranch = execute("git", "rev-parse", "--abbrev-ref", "HEAD").trim()
try {
    execute("git", "checkout", branchName)
    execute("git", "rebase", "origin/master")
    execute("git", "push", "--force-with-lease", "origin", branchName)
    true
} finally {
    if (currentBranch.isNotBlank() && currentBranch != "HEAD") {
        execute("git", "checkout", currentBranch)
    }
}
```
Because the background daemon executes these git commands within the shared root repository directory, it switches branches and mutates the developer's main working tree. If the developer has uncommitted local files or is compiling the project, this branch-swapping causes:
1. Git checkout aborts due to uncommitted files.
2. Silent workspace/IDE pollution or corruption during development.
3. Intermittent compilation errors in IDEs when files change under the feet of the compiler.

**Needed:**
Isolate all orchestrator-driven checkout, rebase, and push operations from the main workspace working tree:
1. Create a dedicated isolated git worktree in a temporary folder using `git worktree add <temp-path> <branch>`.
2. Run the `git rebase origin/master` and `git push --force-with-lease` commands exclusively inside that isolated worktree directory.
3. Clean up and prune the temporary worktree (`git worktree remove --force <temp-path>`) in a `finally` block.
4. If git worktrees are unsupported or fail, fall back to a separate shallow clone in `build/tmp` rather than manipulating the user's active checkout directory.
