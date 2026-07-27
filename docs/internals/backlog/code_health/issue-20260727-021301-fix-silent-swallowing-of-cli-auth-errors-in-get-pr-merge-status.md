---
title: "Fix Silent Swallowing of CLI Authentication Errors in getPrMergeStatus"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
effort: "small"
autonomy: "supervised"
---

# 🔴 [Severity: HIGH]: Fix Silent Swallowing of CLI Authentication Errors in getPrMergeStatus

**Context:**
`GitHubCli.getPrMergeStatus` queries `gh pr view <prNumber> --json mergeable,behindBy` to determine if a PR branch is out of date. However, its error handling catches all `Exception`s and silently defaults to returning `PrMergeStatus("UNKNOWN", 0)`.

If the `gh` CLI invocation fails due to authentication errors (such as `HTTP 401: Bad credentials`), network timeouts, or rate limits, returning `behindBy = 0` causes `OrchestratorStates.kt` (`isBehind = status.behindBy > 0`) to evaluate to `false`. As a result, out-of-date PRs bypass automated branch rebasing without any alert or retry, leaving PR branches stuck out of date and delaying CI execution.

**Needed:**
1. Refactor `GitHubCli.getPrMergeStatus` so it does not silently swallow CLI execution failures or default `behindBy` to `0` when an authentication exception occurs.
2. Introduce an explicit error state in `PrMergeStatus` (e.g. `isError: Boolean` or distinct error status) when `gh` CLI fails to execute cleanly.
3. Update `handleRebaseAndConflicts` in `OrchestratorStates.kt` to check for CLI query failures, send an operator alert when GitHub CLI authentication fails, and retry status retrieval instead of silently skipping branch rebasing.
