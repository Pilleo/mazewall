---
title: "Detect Empty Commits in Jules Review Loop to Prevent Redundant Correction Comments"
severity: "MEDIUM"
status: "open"
priority: 8
dependencies: ["issue-20260726-03"]
component: "orchestrator"
target_modules: [":tools:orchestrator"]
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorEnvironment.kt"
effort: "small"
autonomy: "autonomous"
github_issue: 313
---

# 🔴 [Severity: MEDIUM]: Detect Empty Commits in Jules Review Loop to Prevent Redundant Correction Comments

**Context:**
During `AWAITING_REVIEW`, when Jules is prompted to perform a code review, it frequently responds by pushing commits to the PR branch rather than leaving text review comments. Currently, the Orchestrator leaves correction comments on the PR for up to 2 pushes. However, many of these pushes are empty commits (zero file diffs), leading to repetitive, unhelpful comment loops on GitHub.

**Needed:**
1. Add commit diff inspection method to `GitHubCli.kt` / `OrchestratorEnvironment` (e.g. `isCommitEmpty(prNumber, shaOld, shaNew)` or inspecting `gh pr diff` diff size).
2. Update `AWAITING_REVIEW` state in `OrchestratorStates.kt`:
   - If Jules pushes an **EMPTY commit** (zero file changes): Stop sending redundant PR correction comments. Immediately notify the operator on Telegram (`⚠️ Jules pushed an empty commit during review phase on PR #XYZ`) and pause/escalate to human review.
   - If Jules pushes a **NON-EMPTY commit** (genuine code changes were applied): Treat it as real problem resolution. Reset `julesReviewPushCount` and transition back to `CI_RUNNING` to run tests and request a fresh review once CI passes.
