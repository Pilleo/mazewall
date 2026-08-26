---
title: "Unify Landlock descriptive state with type-state lifecycle in LandlockSession"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/landlock/LandlockState.kt"
target_symbols:
  - "LandlockSession"
needs_kernel: true
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Unify Landlock descriptive state with type-state lifecycle in LandlockSession

**Context:**
Two parallel models describe the same Landlock protocol: (a) `LandlockLifecycle` (`LandlockState.kt:79-111`) — a compile-enforced type-state chain (`RulesetCreated.addRules() -> RulesAdded.restrictSelf() -> Restricted`); and (b) `LandlockSession.tryApplyRuleset()` (`LandlockState.kt:125-199`) — an ~80-line imperative function that mutates a descriptive `var state: LandlockState` at 10 assignment sites inside nested `when`/`try` blocks to record progress for diagnostics. A reviewer must manually verify both stay in sync; nothing prevents the descriptive state from claiming `Applied` while the type-state value was never produced, and any edit to one model can silently desynchronize the other. This is exactly the stateful-code shape that is hardest to review on a security-critical path.

**Needed:**
1. Make `LandlockLifecycle` the single source of truth: have each transition function return both its result and the diagnostic state (or wrap transitions so the session derives `LandlockState` from the last lifecycle step instead of hand-assigning it).
2. Extract `tryApplyRuleset()` steps into small pure functions returning outcome types (`LandlockFdOutcome`, `LandlockRestrictOutcome` already exist) so the session body becomes a flat sequence of step -> check -> next-state, with at most one state write per step.
3. Keep fail-closed behavior identical: every error path must still end in `LandlockState.Failed(error)` plus a rejected `LandlockApplyResult`; no swallowing of errno.
4. Add unit tests (mock `NativeEngine`) asserting the diagnostic state after each failure point matches the lifecycle position (create-ruleset failure vs add-rule failure vs restrict-self failure).
5. While refactoring, fact-check the unsupported-kernel message "Linux 7.0+ (ABI v8)" in `handleProcessWideUnsupported()` against upstream Landlock ABI history and correct it if it is a placeholder.
6. Run `./gradlew :enforcer:test`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260826-102607  file: issue-20260826-102607-unify-landlock-descriptive-state-with-type-state-lifecycle-i.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
