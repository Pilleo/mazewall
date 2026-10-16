---
title: "Refactor InvocationReplayMachine to track re-entrancy depth and process incarnation"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "profiler/src/main/kotlin/io/mazewall/profiler/attribution/InvocationReplayMachine.kt"
target_symbols: 
  - "InvocationReplayMachine"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Refactor InvocationReplayMachine to track re-entrancy depth and process incarnation

**Context:**
InvocationReplayMachine lacks explicit re-entrancy modeling and process lifetime gating, which are prerequisites for fixing re-entrant exact attribution and PID reuse gaps.

**Needed:**
1. Add ReentrantBoundaryState and ProcessIncarnation tokens to TaskState in InvocationReplayMachine

## Resolution Evidence

1. **ProcessIncarnation**: Added `@JvmInline value class ProcessIncarnation(val value: Long)` and attached it to `TaskState`, initialized from the event's `processEpoch`. Re-entrancy and state queries `processIncarnationFor(task)` expose this token.
2. **ReentrantBoundaryState**: Added `data class ReentrantBoundaryState(val depth: Int = 0, val isReentrant: Boolean = depth > 1)` and tracked `reentrantDepth` on `TaskState`. Incremented on `Activate`, decremented on valid `EndRestore`.
3. **Verification**:
   - Unit tests in `InvocationReplayMachineTest` cover re-entrancy depth increments and decrements as well as process incarnation reset across process epochs.
   - Guarded syntax with `./scripts/adkw guard`.
   - Verified zero Detekt warning regressions with `./gradlew detektWarningRatchet`.
   - Verified tests, integration tests, and linters with `./gradlew :profiler:check`.

---
<!-- id: issue-20260912-224643-007-refactor-invocationreplaymachine-to-track-re-entra  file: issue-20260912-224643-007-refactor-invocationreplaymachine-to-track-re-entra.md -->
