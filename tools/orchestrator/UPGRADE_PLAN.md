# Orchestrator Upgrade Plan

This document outlines the required code changes to fix bugs and improve the efficiency of the Orchestrator daemon. **Agents/Developers assigned to implement the fix should follow this specification exactly.**

## 1. Fix Jules Session ID Parsing Bug
**File:** `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/JulesCli.kt`
**Problem:** The `listSessions()` method splits the CLI output by multiple spaces and checks if the first token is a valid Long (`id.toLongOrNull() != null`). Jules session IDs (e.g., `14927969181089226847`) exceed the maximum value of a 64-bit signed integer (`Long.MAX_VALUE`), causing `toLongOrNull()` to evaluate to `null` and drop the session from the list.
**Solution:**
Replace the Long conversion check with a string-based digit check:
```kotlin
// Change this:
if (id.toLongOrNull() != null) { ... }

// To this (or ULong):
if (id.all { it.isDigit() }) { ... }
```

## 2. Implement Token-Efficient PR Review
**File:** `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt`
**Problem:** Passing only the PR number to `agy` forces the agent to use tool calls to fetch the diff, consuming massive amounts of context tokens and increasing latency/failure rates.
**Solution:**
1. Fetch the diff locally in Kotlin:
   ```kotlin
   val prDiff = executeCmd("gh", "pr", "diff", finalPrNumber)
   ```
2. Inject `prDiff` directly into the `agy` prompt text string so the LLM has immediate access to the code changes.

## 3. Graceful Fallback for AI Agent Failures
**File:** `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt`
**Problem:** If `agy` fails (e.g., due to token exhaustion or network errors), the daemon might crash or misbehave.
**Solution:**
Wrap the Antigravity execution block in a `try-catch`. If an exception occurs:
1. Log the failure.
2. Send a Telegram notification: `"⚠️ Automated review failed (Token limit or error). Defaulting to manual human review for PR #$finalPrNumber"`.
3. Bypass the Antigravity approval/rejection logic and immediately enter the manual review waiting loop.

## 4. Auto-Closing Linked GitHub Issues
**File:** `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt`
**Problem:** The orchestrator moves the local backlog file to `resolved/` but leaves the GitHub issue open if the PR doesn't contain a `fixes #` keyword.
**Solution:**
Right after detecting that the PR is merged (`GitHubCli.isPrMerged(finalPrNumber) == true`):
```kotlin
println("Closing GitHub issue #$finalIssueNumber...")
executeCmd("gh", "issue", "close", finalIssueNumber)
bot?.sendMessage("✅ GitHub issue #$finalIssueNumber automatically closed.")
```

## 5. (Optional) State Machine Refactor
*For future consideration:* The main loop inside `OrchestratorDaemon.kt` is deeply nested and difficult to test. Refactoring it into a formal State Machine (e.g., states: `PENDING_APPROVAL`, `AWAITING_JULES_START`, `AWAITING_PR`, `CI_RUNNING`, `AWAITING_REVIEW`, `AWAITING_MERGE`) will significantly improve maintainability.

## 6. Additional Resiliency & Configuration Improvements
*For future consideration:*
- **Configurable Polling/Timeouts:** Make the `sleep` intervals (currently hardcoded to 15s and 30s) and maximum task timeout limits configurable via `.ENV`. This prevents infinite polling if a remote agent stalls.
- **GitHub CLI Request Caching:** The daemon calls `gh` frequently. Implement an in-memory cache with short TTLs for PR state and build status checks to drastically reduce API rate-limiting risk.
- **Persistent State Tracking:** Store active task context (e.g., in a simple local JSON file or SQLite) so that if the Orchestrator Daemon is restarted, it immediately resumes monitoring exactly where it left off without having to re-scan PRs and closed issues.
- **Retry Mechanism:** Implement an exponential backoff retry mechanism for `executeCmd` to handle transient network errors gracefully when calling external CLIs.
