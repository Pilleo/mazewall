---
title: "Split Landlock install into evaluate plus kernel effects"
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
  - "LandlockState"
needs_kernel: true
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "a6f182f7-51fd-48d7-abac-d29f402268ee"
paperclip_identifier: "MAZ-1175"
---

# 🟡 [Severity: MEDIUM]: Split Landlock install into evaluate plus kernel effects

**Context:**
`LandlockSession.tryApplyRuleset` writes `LandlockSession.state` while calling `landlock_create_ruleset` / add / restrict inside a confined arena. CODE_QUALITY.md §12 requires sealed state + `evaluate(state, event)` + effects; do not assign `state =` inside I/O. Unix listen and seccomp connection machines already follow that shape.

**Needed:**
1. Introduce Landlock install events and `evaluate(state, event)` that returns the next state plus effects (`CreateRuleset`, `AddRule`, `RestrictSelf`, `CloseFd`).
2. Run native calls in an interpreter that applies effects and feeds results back as events. Assign `LandlockSession.state` only from `evaluate`.
3. Unit-test the matrix without a real ruleset fd (`MockNativeEngine`).
4. Run `./gradlew :enforcer:test` for Landlock state tests. Kernel tests only if the interpreter path is not covered by mocks.

## Side effects
- LandlockSession.state is assigned only from evaluate results, not during landlock_create_ruleset

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-080955  file: issue-20260907-080955-split-landlock-install-into-evaluate-plus-kernel-effects.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
