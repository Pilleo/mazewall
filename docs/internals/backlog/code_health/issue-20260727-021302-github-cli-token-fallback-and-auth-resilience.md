---
title: "GitHub CLI Authentication Resilience and Fallback for Invalid GITHUB_TOKEN"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20260727-021301"
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt"
effort: "small"
autonomy: "supervised"
---

# 🟡 [Severity: MEDIUM]: GitHub CLI Authentication Resilience and Fallback for Invalid GITHUB_TOKEN

**Context:**
When Orchestrator commands are executed in terminal sessions or subshells where the `GITHUB_TOKEN` environment variable is set to an expired or invalid token, the `gh` CLI attempts to authenticate using `GITHUB_TOKEN` by default and fails with `HTTP 401: Bad credentials`. This failure occurs even if valid local GitHub CLI keyring credentials (`~/.config/gh/hosts.yml`) exist.

Per project rules, agents must not modify or filter `GITHUB_TOKEN` within codebase logic. However, the Orchestrator daemon must be resilient against subshell authentication failures so that transient or bad environment variables do not halt automated PR rebasing and state tracking.

**Needed:**
1. Enhance `RealGitHubClient.executeInDir` to catch `HTTP 401` authentication exceptions when executing `gh` CLI commands.
2. If a `gh` execution fails with `HTTP 401: Bad credentials`, log a clear diagnostic warning to `stderr` indicating that the current environment token is invalid and prompt the operator to check `gh auth status`.
3. Provide robust retry and error diagnostics in `GitHubCli.kt` so authentication errors trigger immediate operator notifications rather than infinite loop retries.
