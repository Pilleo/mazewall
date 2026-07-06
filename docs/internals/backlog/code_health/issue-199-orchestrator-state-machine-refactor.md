---
title: "Orchestrator State Machine Refactor"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
effort: "medium"
---

# 🔴 [Severity: HIGH]: Orchestrator State Machine Refactor

**Context:**
The main polling loop inside `OrchestratorDaemon.kt` is deeply nested, contains complex state tracking variables, and is difficult to unit test and maintain in its current form.

**Needed:**
Refactor the daemon's core logic into a formal State Machine pattern. 
1. Define formal state classes or enum handlers (e.g. `SELECT_TASK`, `PENDING_APPROVAL`, `AWAITING_JULES_START`, `AWAITING_PR`, `CI_RUNNING`, `AWAITING_REVIEW`, `AWAITING_MERGE`).
2. Encapsulate state transition logic cleanly, separating network/CLI actions from core control flow.
3. Improve testability of the loop execution by decoupling CLI commands from the state transitions.
