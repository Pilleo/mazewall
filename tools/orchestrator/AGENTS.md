# Agent Guidelines for Orchestrator Module

This module contains the `Autonomous Backlog Orchestrator` which acts as the central control loop for task lifecycle automation.

## Key Architectural Constraints

1. **CLI Wrappers**
   - The Orchestrator heavily relies on external CLI tools (`gh`, `jules`, `agy`).
   - Code inside `GitHubCli.kt` and `JulesCli.kt` must remain robust wrappers. Do not attempt to replace these with raw API calls (like Retrofit/Ktor) without explicit instruction.
   - When parsing CLI output (especially tables or JSON), prefer JSON output flags (e.g., `gh issue list --json`) over brittle regex table parsing.

2. **State Machine Architecture**
   - The loop in `OrchestratorDaemon.kt` executes as a State Machine running state handlers in `OrchestratorDaemonRunner`.
   - **Do not revert to a monolithic nested loop.** Keep state transitions explicit and well-defined inside separate handler functions.
   - Any new state added to `OrchestratorState` must be serialized/deserialized in `OrchestratorContext` and handled inside `OrchestratorDaemonRunner.run()`.

3. **State Persistence and Serialization**
   - The daemon relies on `.orchestrator_state.properties` for state recovery after restarts.
   - When introducing new variables to the task tracking context, they **must** be added to `OrchestratorContext` properties serialization/deserialization methods (`load` and `save`).
   - Keep state file serialization free of external library dependencies by strictly utilizing Java `Properties`.

4. **Error Handling, Resiliency, & Anti-Spam**
   - All critical external calls within state handlers must catch errors locally and schedule a retry/sleep cycle instead of crashing the daemon.
   - Keep anti-spam safeguards intact: Use `lastFailedSha` to prevent duplicate build failure comments on PRs for the same failing commit.
   - Ensure you use `java.util.concurrent.TimeUnit` sleep helpers for readability.
