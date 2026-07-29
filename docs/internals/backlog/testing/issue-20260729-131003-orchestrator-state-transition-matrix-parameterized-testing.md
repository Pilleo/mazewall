---
title: "Implement Parameterized Transition Matrix Testing for Orchestrator States"
severity: "HIGH"
status: "open"
priority: 10
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/StateHandlerTest.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Implement Parameterized Transition Matrix Testing for Orchestrator States

**Context:**
The Autonomous Backlog Orchestrator is highly stateful, managing transition protocols across 8 primary states: `SELECT_TASK`, `PENDING_APPROVAL`, `AWAITING_JULES_START`, `AWAITING_PR`, `CI_RUNNING`, `AWAITING_REVIEW`, `AWAITING_MERGE`, and `RESOLVE_TASK`.
Currently, the unit tests under `StateHandlerTest.kt` and `OrchestratorTest.kt` test individual state transitions and behaviors in isolation. However, due to the high combinatorial space of state variables (such as branch SHA changes, empty/non-empty commits, GitHub issue/PR closures, Jules session statuses, and rebase outcomes), edge cases are easily missed, leading to critical runtime hangs or state-machine deadlocks in production.

**Needed:**
1. Introduce a comprehensive, parameterized "Transition Matrix" test harness inside `:tools:orchestrator`.
2. Define a structured representation of the system inputs (e.g., initial state, PR status, Jules session state, build outcome, mergeable status, git conflicts).
3. Using JUnit 5 `@ParameterizedTest` and a `@MethodSource` generator, execute a complete state transition matrix (covering dozens of distinct state/input combinations) to assert that the state machine transitions to the mathematically correct next state or aborts with correct logs.
4. This test harness must cover the following critical combinations:
   - `CI_RUNNING` -> `AWAITING_REVIEW` under successful build.
   - `CI_RUNNING` -> `CI_RUNNING` under pending build with rebase checks.
   - `AWAITING_REVIEW` -> `CI_RUNNING` when Jules pushes a non-empty code commit instead of a review comment.
   - `AWAITING_REVIEW` -> `AWAITING_MERGE` when Jules pushes an empty commit (should halt redundant cycles).
   - Abrupt GitHub issue closures during `PENDING_APPROVAL`, `AWAITING_JULES_START`, and `AWAITING_PR`.
