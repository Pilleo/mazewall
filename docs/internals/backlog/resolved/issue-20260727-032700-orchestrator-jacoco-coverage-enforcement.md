---
title: "Configure Jacoco Coverage Enforcement (80%) for :tools:orchestrator"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "build.gradle.kts"
  - "tools/orchestrator/build.gradle.kts"
effort: "large"
autonomy: "supervised"
github_issue: 359
---

# 🔴 [Severity: HIGH]: Configure Jacoco Coverage Enforcement (80%) for :tools:orchestrator

**Context:**
Currently, `:enforcer` (82%) and `:profiler` (71%) enforce Jacoco instruction coverage thresholds during build and CI verification via `build.gradle.kts`. However, `:tools:orchestrator` has no Jacoco coverage verification rule configured in `violationRules`, and its current instruction coverage stands at **44.4%** (5,536 / 12,467 instructions covered).

Key coverage gaps identified:
1. **`RealGitHubClient` (0% covered, 2,003 instructions):** Tight coupling to raw `ProcessBuilder` shell executions (`gh` CLI commands).
2. **`RealJulesClient` (0% covered, 707 instructions):** Direct HTTP / CLI calls to Jules REST API endpoints without mockable transport interfaces.
3. **`TelegramBot` (0% covered, 582 instructions):** Monolithic polling and notification logic tightly coupled to Telegram HTTP APIs.
4. **`RealOrchestratorEnvironment` (0% covered, 406 instructions):** Direct system-level side effects (printing, sleeping, executing commands).
5. **State Handlers (`AWAITING_PR`, `CI_RUNNING`, `AWAITING_REVIEW`, `AWAITING_MERGE`):** Partially covered (36.6% - 75.5%) due to untested edge-case branches (rebase retries, empty commit pushes, timeout stuck notifications, failure logging).

**Needed:**
1. **Configure Jacoco Coverage Minimum (80%):**
   - Add Jacoco `violationRules` for `:tools:orchestrator` in `build.gradle.kts` with an 80% instruction coverage target (`minimum = "0.80".toBigDecimal()`).
   - Add appropriate `jacocoExcludes` patterns for un-testable bootstrap main entry points (`**/io/mazewall/orchestrator/OrchestratorDaemonKt*`) if necessary.
2. **Increase Unit & Integration Test Coverage:**
   - Expand `StateHandlerTest.kt`, `BacklogParserEnhancedTest.kt`, and `BacklogValidatorTest.kt` to cover state transition edge cases, failure states, empty commit pushes, and rebase recovery paths.
3. **Create Modular Refactoring Issues if Blocked by Testability:**
   If achieving 80% coverage on specific components (such as `RealGitHubClient`, `RealJulesClient`, or `TelegramBot`) requires breaking architectural changes or interface decoupling, create dedicated backlog issue files under `docs/internals/backlog/code_health/` detailing the specific refactoring required:
   - Interface decoupling for `ProcessBuilder` execution in `RealGitHubClient`.
   - Mockable HTTP transport abstraction for `RealJulesClient` and `TelegramBot`.
