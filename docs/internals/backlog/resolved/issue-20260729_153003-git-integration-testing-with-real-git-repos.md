---
title: "Implement Real Git Repository Integration Tests for Orchestrator BranchRebaser"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/BranchRebaserGitIntegrationTest.kt"
effort: "medium"
autonomy: "supervised"
---

# 🔴 [Severity: MEDIUM]: Implement Real Git Repository Integration Tests for Orchestrator BranchRebaser

**Context:**
The `BranchRebaser` component manages git operations inside the Autonomous Backlog Orchestrator. It fetches branches, sets up detached worktrees, performs standard git merges, checkouts target files for rescue flows, pushes branch modifications, and cleans up worktrees.

Currently, `BranchRebaserTest.kt` verifies the rebaser's logic by mocking `execute` and `executeInDir` callbacks (string list inputs) and asserting on mock outputs.

**The Testing Gap:**
While mock-based unit tests are useful for basic state coverage, they cannot verify the correctness of the actual Git command executions, working-tree manipulation, or boundary behaviors under real filesystems. If the local Git client version changes, output formats differ, or subtle repository permissions occur, the daemon could fail or corrupt branches silently in production without any test failures.

**Needed:**
1. Create a dedicated integration test suite `BranchRebaserGitIntegrationTest.kt` under `:tools:orchestrator`'s test source set.
2. The test setup must:
   - Use a temporary directory as a mock Git workspace (`git init`).
   - Configure a dummy username and email for git commits.
   - Setup a "remote" repository (bare or standard) to simulate `origin`.
   - Create a master branch with initial files and commits.
3. Using the real Git command executions (pointing `execute` and `executeInDir` to run actual system processes on the local machine), verify the following scenarios:
   - Successful merge of master into a PR branch when there are no conflicts.
   - Successful self-healing branch recovery (recovering allowed files and discarding unintended alterations) when unrelated histories or merge conflicts are encountered.
   - Correct cleanup of temporary git worktrees (`git worktree remove` and `git worktree prune`) on both successful completions and exceptional failures.
