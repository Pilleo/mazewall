---
title: "Make OrchestratorEvent evaluate exhaustive instead of else no-op"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
target_symbols:
  - "OrchestratorEvent"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "6741a95a-e71a-470b-8b93-13a728ddbb25"
paperclip_identifier: "MAZ-1167"
---

# 🟡 [Severity: MEDIUM]: Make OrchestratorEvent evaluate exhaustive instead of else no-op

**Context:**
`OrchestratorEvent` is sealed, but each `OrchestratorState.evaluate()` ends with `else -> Transition(this)`. A new event is a silent no-op unless every handler is updated by hand. That is the same class of review hazard as security-machine `else` branches: the compiler will not fail.

**Needed:**
1. Remove `else ->` from every `evaluate` on `OrchestratorEvent`. List each event variant per state; unused events must be an explicit `Transition(this)` branch, not a catch-all.
2. `:tools` does not run Detekt; keep the exhaustive `when` as a compile-time requirement in this module (no `else ->` on `OrchestratorEvent`).
3. Run `./gradlew :tools:orchestrator:test -PincludeOrchestrator=true`.

## Side effects
- New OrchestratorEvent subtypes become compile errors until each state handles them

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-080802  file: issue-20260907-080802-make-orchestratorevent-evaluate-exhaustive-instead-of-else-n.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
