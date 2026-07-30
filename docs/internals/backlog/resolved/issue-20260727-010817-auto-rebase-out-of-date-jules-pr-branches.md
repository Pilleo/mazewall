---
title: "Automated Rebase and Conflict Resolution for Out-of-Date Jules PR Branches in Orchestrator"
severity: "HIGH"
status: "resolved"
priority: 9
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
effort: "medium"
autonomy: "supervised"
github_issue: 348
---

# 🔴 [Severity: HIGH]: Automated Rebase and Conflict Resolution for Out-of-Date Jules PR Branches in Orchestrator

**Context:**
When Jules opens a PR or pushes a review commit, master may advance concurrently as other PRs merge. If the PR branch becomes out of date or enters a `CONFLICT` state, GitHub CI builds may fail to trigger, remain pending, or block automated merging.

Because Jules sessions run asynchronously in isolated cloud containers, Jules cannot continuously monitor or rebase its PR branches once a task session completes. Therefore, branch freshness and conflict resolution must be governed locally and automatically by the **Orchestrator daemon** control plane.

**Needed:**
1. Enhance `GitHubCli.kt` to query PR `mergeable` status and `behindBy` commit delta via `gh pr view <prNumber> --json mergeable,behindBy`.
2. Update `OrchestratorStates` (`CI_RUNNING`, `AWAITING_PR`, `AWAITING_REVIEW`) to automatically detect when an active PR branch is behind `master` or blocked due to conflict status.
3. Attempt server-side GitHub API rebase first using `gh pr update-branch <prNumber> --rebase`.
4. If server-side API rebase fails due to structural code conflicts, fall back to an isolated local git worktree (`git worktree add ../temp-rebase-<pr>`), fetch `origin/master`, execute `git rebase origin/master`, verify compilation (`./gradlew compileKotlin`), and force-push back (`git push --force-with-lease origin <branch>`).
5. If automated local worktree rebase fails due to unresolvable code conflicts, notify the operator via Telegram with the PR link and flag the task for human intervention.
