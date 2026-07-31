---
title: "Refactor Orchestrator States with Command/Event Pattern for Side-Effect Isolation"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Refactor Orchestrator States with Command/Event Pattern for Side-Effect Isolation

**Context:**
The `execute` method inside `OrchestratorState` contains nested logical transition checks mixed directly with imperative side effects (such as CLI executions, Telegram message dispatches, and file reads). Because these side-effects are executed eagerly, testing the state transition graph requires constructing a massive and fragile `MockOrchestratorEnvironment` that mocks every possible CLI response.

**Needed:**
1. Refactor the `OrchestratorState` state machine to make its decision logic completely pure. The `execute` (or a new `evaluate`) method should accept the current state, `SlotContext`, and an `Event` object (e.g. `PrBuildStatusFetched`, `TelegramApprovalReceived`), and return a `Transition` containing the next state and a list of `OrchestratorCommand` instances.
2. Define `OrchestratorCommand` as a sealed class representing the intent to perform side effects (e.g., `CreateGitHubIssue`, `SendTelegramNotification`, `CommentOnPr`, `QueryPrMergeStatus`).
3. Implement a centralized, imperative `CommandInterpreter` in `OrchestratorDaemon.kt` that processes the list of commands returned by the state machine and performs the I/O operations against the `OrchestratorEnvironment`.
4. Ensure 100% test coverage of state machine transitions in a new `StateTransitionTest.kt` by passing events and asserting on the returned state and commands, without mocking any CLI process or network client.

**Verification/Regression Tests:**
- Validate that selecting a prioritized task generates exactly a `CreateGitHubIssue` command and transitions to `PendingApprovalState`.
- Validate that a PR build failure event generates exactly a `CommentOnPr` command with the build logs and retains the `CiRunningState` with a delay.
- Run `./gradlew :tools:orchestrator:test` to guarantee full coverage of the new state evaluation logic.
