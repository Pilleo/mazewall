# Orchestrator Code Review & Improvement Plan

## Overview
The Orchestrator Daemon (`OrchestratorDaemon.kt`) acts as an autonomous loop that:
1.  **Reads issues** from a Markdown-based backlog.
2.  **Resolves dependencies** via a basic `DependencyGraph`.
3.  **Prompts for approval** via terminal or Telegram bot.
4.  **Triggers a Jules session** using GitHub CLI & local scripts to work on the issue.
5.  **Monitors PR** creation, build status, merge conflicts.
6.  **Triggers Jules for review** and feedback on failure.
7.  **Marks issue as resolved** upon PR merge.

## Architecture & Code Quality
- **Monolithic State Machine**: The core logic is a giant loop inside `OrchestratorDaemonRunner.run()` that handles a large enum of states (`OrchestratorState`).
- **Coupling**: The state machine directly interacts with external services (GitHub via `GitHubCli.kt`, Telegram via `TelegramBot.kt`, Process execution).
- **Testability**: The `OrchestratorDaemon` is heavily coupled to filesystem states, process execution (`executeCmd`), and network calls. It is almost untestable in its current form. The only tests found are for `BacklogParser` and `DependencyGraph`.
- **Error Handling**: Uses broad try-catch blocks and simple thread sleeps. `executeCmd` uses `waitFor(10, TimeUnit.MINUTES)` directly on `ProcessBuilder` which blocks the current thread heavily.
- **State Persistence**: Uses a Java Properties file. Fragile and lacks schema validation.

## Bugs & Edge Cases Spotted
1.  **Race conditions in PR monitoring**: The checks for PR status, merge status, and comments rely on CLI polling which can easily desync.
2.  **Lack of Timeout/Circuit Breakers**: States like `AWAIT_JULES_START` and `MONITOR_PR` loop indefinitely if things get stuck (e.g., if a PR is never created or never reviewed).
3.  **File System Dependency**: Resolving issues relies on string replacements in files (`replaceFirst("status: \"open\"", "status: \"resolved\"")`), which is prone to format breakage.
4.  **Security**: Direct execution of external binaries (`gh`, `jules`, `bash`) with concatenated arguments can be a command injection risk if not strictly controlled.

## Recommendations for Improvement

### 1. State Machine Redesign
Adopt a **type-safe state machine** (e.g., using `sealed class`es in Kotlin) representing the current state and its valid transitions. This makes illegal states unrepresentable and vastly improves testability.

### 2. Dependency Inversion / Modularity
Introduce interfaces for external interactions:
- `IssueTracker` (currently implemented by `GitHubCli`)
- `Notifier` (currently `TelegramBot` / Terminal)
- `AgentClient` (currently `JulesCli`)
- `BacklogRepository` (currently `BacklogParser` / File I/O)

This allows mocking these dependencies in tests, covering the state machine logic thoroughly.

### 3. Resilience and Polling
- Implement proper exponential backoff for API polling instead of static `TimeUnit.SECONDS.sleep(30)`.
- Use timeouts for states that wait on external events.
- Transition from property files to a more robust serialization format (JSON/YAML with Kotlinx Serialization) for state persistence.

### 4. Improve Process Execution
Instead of raw `ProcessBuilder` and `bufferedReader().readLine()`, use a dedicated library (e.g., `kotlinx-coroutines-core` for async process management) or better abstracted wrappers that handle timeouts and error streams more gracefully.

## Action Plan
1. Refactor `OrchestratorState` into a type-safe `sealed class` hierarchy holding relevant state data.
2. Extract the monolithic `handle*` methods into discrete state handler classes or functions.
3. Introduce interfaces for external dependencies (GitHub, Telegram, Jules).
4. Add unit tests for the state transitions using mocks.
