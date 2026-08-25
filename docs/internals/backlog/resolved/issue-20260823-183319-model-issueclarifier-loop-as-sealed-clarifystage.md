---
title: "Model IssueClarifier loop as sealed ClarifyStage"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - "issue-20260823-182948"
  - "issue-20260823-183258"
  - "issue-20260823-183303"
  - "issue-20260823-183307"
  - "issue-20260823-183312"
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueClarifier.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/IssueTemplateGeneratorTest.kt"
target_symbols:
  - "IssueClarifier"
verify_cheap:
  - "./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Model IssueClarifier loop as sealed ClarifyStage

**Context:**
`tryClarify` is a nested imperative procedure (author, impact, investigate while, leftover, review repeat). Further skip/retry/budget rules will keep adding `if`s. mazewall craftsmanship requires sealed state + `evaluate(state, event)` for new protocol loops, with effects applied outside evaluate. Do this **after** the behavior issues in `dependencies` so the machine encodes the finished policy, not an intermediate one.

**Needed:**
1. `internal sealed interface ClarifyStage` covering at least Author, Impact, Investigate, Leftover, Review, Done (names may vary; exhaustive `when`).
2. Pure `evaluate(stage, event): Pair<ClarifyStage, List<ClarifyEffect>>` with no I/O. Effects: `CallWeak`, `CallStrong`, `ScanImpact`, `Warn`, `Stop`.
3. `tryClarify` becomes an interpreter of those effects (ACP, scanner, verifier).
4. Unit-test the matrix without ACP: e.g. no-shrink investigate → Leftover; not-ready → Done skipped; `has_side_effects` with concrete hits → skip extra side-effects call.
5. Existing `IssueTemplateGeneratorTest` clarify tests stay green.
6. Do not change ACP wire format in this issue.

## Investigation
- `IssueClarifier.tryClarify` ~150 lines of control flow.
- CODE_QUALITY.md §9 / §12: sealed hierarchies for workflows; no `state =` inside I/O.

## Important details
- Weak vs strong remain separate `ChatModel` instances.
- Keep `maxWeakRounds` / `maxReviewLoops` as interpreter budgets, not magic inside evaluate unless encoded as stage data.

## Side effects
- `tryClarify` control flow moves into `evaluate(state, event)`; all clarify tests stay green.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest -PincludeOrchestrator=true`.

<!-- id: issue-20260823-183319  file: issue-20260823-183319-model-issueclarifier-loop-as-sealed-clarifystage.md -->
