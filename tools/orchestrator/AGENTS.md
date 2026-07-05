# Agent Guidelines for Orchestrator Module

This module contains the `Autonomous Backlog Orchestrator` which acts as the central control loop for task lifecycle automation.

## Key Architectural Constraints

1. **CLI Wrappers**
   - The Orchestrator heavily relies on external CLI tools (`gh`, `jules`, `agy`).
   - Code inside `GitHubCli.kt` and `JulesCli.kt` must remain robust wrappers. Do not attempt to replace these with raw API calls (like Retrofit/Ktor) without explicit instruction.
   - When parsing CLI output (especially tables or JSON), prefer JSON output flags (e.g., `gh issue list --json`) over brittle regex table parsing.

2. **Execution Loops and Halts**
   - `OrchestratorDaemon.kt` contains the main `while(true)` loop.
   - Operations that sleep or wait for long periods (e.g., waiting for Jules sessions or PR merges) are expected behavior.
   - **Do not introduce arbitrary timeouts that kill the main process.** The script is meant to be resilient and run infinitely in the background.

3. **Error Handling & Resiliency**
   - Any external CLI invocation can fail. Network requests fail. Rate limits get hit.
   - All critical external calls within the main loop must be wrapped in try-catch blocks or use resilient fallback paths so the daemon does not crash.
   - Graceful degradation: If `agy` (Antigravity) fails or runs out of tokens during a PR review, the flow must log the error and transition to a manual review state rather than crashing or infinitely looping.

4. **Code Generation and Formatting**
   - Follow standard Kotlin conventions.
   - Ensure you use `java.util.concurrent.TimeUnit.SECONDS.sleep()` instead of `Thread.sleep()` for readability.

## Prototyping Future Improvements

When instructed to upgrade or refactor the Orchestrator, consult `tools/orchestrator/UPGRADE_PLAN.md` for specific planned changes.
Do not implement changes to execution logic in this module unless explicitly asked to do so by the user.
