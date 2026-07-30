---
title: "GitHub CLI Token Precedence and Environment Authentication Resilience"
severity: "MEDIUM"
status: "open"
priority: 8
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt"
---

# 🟡 [Severity: MEDIUM]: GitHub CLI Token Precedence and Environment Authentication Resilience

## Context
When running `gh` CLI commands inside subshell process execution environments (such as IDE test runners or automated agent subshells), environment variable injection can introduce invalid or dummy `GITHUB_TOKEN` values (e.g. `GITHUB_TOKEN=github_pat_antigravitydummytoken`).

GitHub CLI (`gh`) enforces strict authentication precedence rules:
1. If the `GITHUB_TOKEN` environment variable exists (even if invalid or dummy), `gh` MUST prioritize `GITHUB_TOKEN` over keyring credentials (`gh auth login`).
2. Tries to authenticate against GitHub GraphQL/REST APIs using `GITHUB_TOKEN`, resulting in `HTTP 401: Bad credentials`.
3. System keyring credentials (`gho_...`) marked as active in `gh auth status` are ignored.

Because project safety guidelines explicitly prohibit modifying or filtering `GITHUB_TOKEN` directly inside application code (*"Never modify, filter, or handle the `GITHUB_TOKEN` environment variable in the codebase"*), the Orchestrator must detect and report credential environment mismatches gracefully at startup.

## Discovery
1. `gh auth status` output when `GITHUB_TOKEN` is present:
   ```text
   github.com
     X Failed to log in to github.com using token (GITHUB_TOKEN)
     - Active account: true
     - The token in GITHUB_TOKEN is invalid.

     ✓ Logged in to github.com account Pilleo (keyring)
     - Active account: false
   ```
2. When launching the Orchestrator daemon in terminals where `GITHUB_TOKEN` is exported with a valid token (`export GITHUB_TOKEN=$(env -u GITHUB_TOKEN gh auth token)`), `gh` succeeds cleanly.
3. Unsetting `GITHUB_TOKEN` allows `gh` to fall back to the system keyring account (`Pilleo`).

## Needed
1. **Startup Credential Verification Diagnostic**:
   - Improve `verifyCredentials()` in `GitHubCli.kt` to explicitly parse `gh auth status` output and alert the operator if `GITHUB_TOKEN` is active but invalid.
   - Print clear, human-readable troubleshooting instructions on startup:
     - `Option 1: Unset GITHUB_TOKEN (unset GITHUB_TOKEN) to use keyring credentials.`
     - `Option 2: Export a valid GITHUB_TOKEN (export GITHUB_TOKEN=$(env -u GITHUB_TOKEN gh auth token)).`
2. **Backoff and Recovery Resilience**:
   - Ensure credential failure diagnostic alerts notify the operator via Telegram/console logs without corrupting state machine retry timers.
