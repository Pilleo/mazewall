---
title: Refactor Orchestrator State Machine Testability
priority: 8
status: resolved
dependencies:
- issue-190
severity: HIGH
target_files:
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt
target_modules:
- :tools:orchestrator
component: orchestrator
effort: MEDIUM
github_issue: 249
---

# Refactor Orchestrator State Machine Testability

**Context:**
The state transition logic inside `OrchestratorStates.kt` heavily relies on large `env: OrchestratorEnvironment` instances which are cumbersome to mock. Furthermore, some states include complex polling logic and side-effects embedded directly inside the `execute` method, making them untestable without huge setup code that merely "exercises mock triggers".

**Needed:**
We need to decouple the state transitions from the side effects to improve testability.
- Split the `execute` method in the `OrchestratorState` interface into pure decision logic (`evaluateTransition`) and side-effect logic (`performSideEffects`).
- This allows testing the state machine's logical transitions cleanly by passing in specific environment states and observing the returned target state, without actually performing network I/O or shell commands during test runs.
- Once refactored, the test coverage can easily and meaningfully reach >80% without useless mock-heavy tests.
