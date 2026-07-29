---
title: "Refactor Orchestrator State Machine to Enforce Compile-Time State Invariants via Type-States"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
effort: "large"
autonomy: "supervised"
---

# 🔴 [Severity: HIGH]: Refactor Orchestrator State Machine to Enforce Compile-Time State Invariants via Type-States

**Context:**
The orchestrator's state transitions currently use a sealed interface `OrchestratorState` where each state is a singleton `data object` executing over a global `SlotContext` and `OrchestratorContext`.
The `SlotContext` contains mutable, nullable variables (like `prNumber`, `julesSessionId`, `githubIssueNumber`, `lastHeadSha`, `lastReviewedSha`, etc.) representing the collective union of all parameters needed across any state.

**The Architectural Problem:**
This creates a "Primitive Obsession" and weak type-safety design where any state can read or mutate any variable, and there is no compile-time guarantee that the variables required by a particular state have actually been initialized or are present. For example:
- `CI_RUNNING` requires `prNumber`, but accessing it requires unsafe null-checks or throwing `IllegalStateException("prNumber is null")`.
- `AWAITING_REVIEW` also assumes `julesSessionId` is populated if a session was previously linked, but this is not guaranteed by construction.
- Variables can leak across states or be modified accidentally.

This violates our core architectural principle of **Type-State Machine Pattern (Safety-by-Construction)** where invalid state combinations and operation sequences must be made unrepresentable in the type system.

**Needed:**
1. Refactor the Orchestrator's state machine to use a true Type-State representation where each state is represented by a distinct class holding *only* the specific, non-null data required for that phase:
   - `class SelectTaskState`
   - `class PendingApprovalState(val issueId: String, val issueTitle: String)`
   - `class AwaitingJulesStartState(val issueId: String, val githubIssueNumber: String)`
   - `class AwaitingPrState(val issueId: String, val githubIssueNumber: String, val julesSessionId: String)`
   - `class CiRunningState(val issueId: String, val githubIssueNumber: String, val julesSessionId: String, val prNumber: String)`
   - `class AwaitingReviewState(val issueId: String, val githubIssueNumber: String, val julesSessionId: String, val prNumber: String, val lastHeadSha: String)`
2. Ensure transitions between these classes are strongly typed: the `execute` method of a state class must return the specific next state class or a sealed union of permissible successor states.
3. Update `OrchestratorContext` and persistence logic to deserialize from the properties format back into the appropriate type-safe state objects.
