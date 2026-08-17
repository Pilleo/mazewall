---
title: Refactor GitHubCli and JulesCli for Testability
priority: high
status: resolved
dependencies: []
severity: HIGH
target_files:
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/GitHubCli.kt
target_modules:
- :tools:orchestrator
component: orchestrator
effort: MEDIUM
github_issue: 251
---

# Refactor GitHubCli and JulesCli for Testability

**Context:**
The current `GitHubCli` and `JulesCli` classes in the `:tools:orchestrator` module are implemented as singletons (Kotlin `object`s) that directly execute system processes (via `ProcessBuilder`) or make direct REST calls (via `HttpClient`). This design makes them extremely difficult to unit test without executing actual GitHub CLI commands or making real HTTP calls to the Jules API, which leads to flaky tests and necessitates excessive mocking.

**Needed:**
We need to refactor these classes into interfaces with default concrete implementations that can be injected.
- Introduce `GitHubClient` and `JulesClient` interfaces.
- Modify the `OrchestratorEnvironment` (or create a context registry) to provide instances of these interfaces rather than relying on global object state.
- Create lightweight mocks or fakes for these interfaces in unit tests.
- This will allow the `OrchestratorStates` and daemon loops to be thoroughly tested without invoking real process executions or HTTP requests, enabling the module to reach its >80% coverage goal reliably.
