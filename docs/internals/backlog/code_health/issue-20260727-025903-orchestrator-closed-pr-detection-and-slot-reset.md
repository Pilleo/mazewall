---
title: "Detect Closed Pull Requests and Suppress Conflict Alarm Spam in Orchestrator"
severity: "MEDIUM"
status: "open"
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

# 🟡 [Severity: MEDIUM]: Detect Closed Pull Requests and Suppress Conflict Alarm Spam in Orchestrator

**Context:**
When a PR is closed or replaced by the operator (or when a new Jules session is manually triggered to replace an old PR), the Orchestrator continues tracking the old PR number stored in `slot.prNumber`. 

Currently:
1. `GitHubClient` only provides `isIssueClosed(issueNumber)`, but has no `isPrClosed(prNumber)` query.
2. In `handleRebaseAndConflicts(env, slot, prNumber)`, when an old PR is closed on GitHub, local/remote rebase fails, causing Orchestrator to ring the terminal bell (`ringBell(3)`) and send notification alerts every 60 seconds indefinitely (`🔔 [CONFLICT] PR #349 has conflicts!`).
3. `slot.prNumber` remains bound to the closed PR, preventing Orchestrator from automatically discovering and binding to any new replacement PR opened for the issue.

**Needed:**
1. **Add `isPrClosed(prNumber: String): Boolean` to `GitHubClient` & `GitHubCli`:**
   Query `gh pr view <prNumber> --json state` to check if the PR state is `"CLOSED"` or `"MERGED"`.
2. **Handle Closed PRs in `handleRebaseAndConflicts` & State Handlers:**
   When `isPrClosed(prNumber)` returns true:
   - Suppress rebase retries and disable conflict alarm notifications.
   - Clear `slot.prNumber = null` to reset the slot's PR handle cleanly.
   - Print log message: `ℹ️ PR #$prNumber was closed/merged on GitHub. Resetting PR handle for slot.`
3. **Automate Replacement PR Discovery:**
   When `slot.prNumber` is reset to `null` due to PR closure, state transitions (`AWAITING_REVIEW`, `CI_RUNNING`) should automatically transition back to `AWAITING_PR` to discover any new PR opened for the active issue without requiring a daemon restart.
4. **Unit Tests:**
   Add unit tests in `StateHandlerTest.kt` verifying that closed PRs do not trigger rebase alerts and result in resetting `slot.prNumber`.
