---
title: "Refactor Orchestrator States to Be Fully Non-Blocking to Enable True Concurrent Task Execution"
severity: "HIGH"
status: "open"
priority: 9
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
effort: "medium"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Refactor Orchestrator States to Be Fully Non-Blocking to Enable True Concurrent Task Execution

**Context:**
The Backlog Orchestrator runner loop in `OrchestratorDaemonRunner` iterates sequentially over all active slots:
```kotlin
                    for (slot in slotsToProcess) {
                        try {
                            val nextState = slot.state.execute(env, context, slot)
                            ...
```
Because the runner is single-threaded, if any state's `execute()` method blocks or sleeps, it blocks the execution of all other active tasks.

Currently, several states call `env.sleep(...)` or block in while loops inside `execute()`:
1. `AwaitingJulesStartState`: Runs a blocking `while` loop that sleeps for `julesTriggerIntervalSeconds` on every attempt (up to 12 attempts * 15s = 180 seconds).
2. `CiRunningState` and `AwaitingReviewState`: Call `env.sleep(pollingIntervalSeconds, ...)` (30 seconds) inside `execute()` whenever a Jules session is in progress, a rebase check occurs, or a review request is made.
3. `CiRunningState`: Calls `env.sleep(ciFailureRetryMinutes, ...)` (5 minutes) inline on build failure.

Whenever any active slot encounters these conditions, the entire daemon runner is completely blocked. This results in severe latency and entirely destroys the benefits of the multi-slot parallel task scheduler, turning a parallel execution engine into a sequentially blocked pipeline.

**Needed:**
1. Refactor all states in `OrchestratorStates.kt` to make their execution fully non-blocking. No state's `execute()` method should ever invoke `env.sleep(...)` or contain blocking loops.
2. For long-running polling or retry delays (e.g., CI failures or waiting for Jules), utilize a timestamp-based approach using slot/context properties (such as tracking `lastCheckedTime` or `retryAfterTime` in `SlotContext`):
   - Before executing the status check, check if `currentTime < retryAfterTime`. If so, immediately return `this` (the current state) to yield control back to the runner so other slots can be processed.
3. Remove redundant inline sleep calls, and rely solely on the main loop's end sleep (e.g. `env.sleep(5, TimeUnit.SECONDS)`) to regulate the loop's overall frequency.
4. Update unit tests in `StateHandlerTest.kt` to mock non-blocking environments and assert that states execute immediately and yield rather than calling blocking sleep operations.
