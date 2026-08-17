---
title: "Add Mandatory Origin Master Rebase Prompt Instructions in Jules Retry and Feedback Prompts"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorPrompts.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/OrchestratorPromptsTest.kt"
effort: "small"
autonomy: "supervised"
---

# 🔴 [Severity: HIGH]: Add Mandatory Origin Master Rebase Prompt Instructions in Jules Retry and Feedback Prompts

**Context:**
Jules operates in an isolated cloud workspace initialized when a task session starts. When a human operator or Orchestrator merges `master` into a PR branch on GitHub, Jules's active cloud workspace does not automatically pull the updated remote branch.

When CI fails or review feedback is requested, Orchestrator sends logs/comments to Jules. Jules applies fixes in its local cloud environment (still based on the older pre-rebase commit) and pushes to GitHub. This push overwrites the PR branch, discarding the master merge commit, reverting unrelated master changes, and causing the PR to become outdated (`BEHIND`) again.

**Needed:**
1. **Update `OrchestratorPrompts.kt`:**
   - Update `taskPrompt`, CI failure comment prompts, review feedback prompts, and session retry prompts to include an explicit mandatory pre-push rebase instruction:
     > *"🚨 **MANDATORY PRE-PUSH REBASE INSTRUCTION**: Before modifying code or pushing changes, you MUST execute `git fetch origin master && git rebase origin/master` in your workspace to incorporate the latest master changes and prevent overwriting master commits."*
2. **Apply in Failure & Feedback Comments:**
   - Ensure `CI_RUNNING` failure comments (when posting build failure logs to PRs) and retry messages include this rebase instruction prompt.
3. **Unit Tests:**
   - Add unit tests in `OrchestratorPromptsTest.kt` verifying that all generated task and feedback prompts contain the mandatory rebase instruction snippet.
