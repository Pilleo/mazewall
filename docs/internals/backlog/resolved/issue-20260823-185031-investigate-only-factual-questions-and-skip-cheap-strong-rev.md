---
title: "Investigate only factual questions and skip cheap strong review"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - "issue-20260823-185028"
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueClarifier.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/ClarifyStage.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/IssueTemplateGeneratorTest.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/ClarifyStageTest.kt"
target_symbols:
  - "ClarifyPolicy"
  - "IssueClarifier"
verify_cheap:
  - "./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest"
  - "./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.ClarifyStageTest"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Investigate only factual questions and skip cheap strong review

**Context:**
Weak investigate still receives operator questions. Strong final review still runs on comment-only issues. Jules never hits this code, but `--clarify` cost is dominated by those extra calls. Policy: investigate only `QuestionKind.FACTUAL`; default `maxWeakRounds = 1`; skip strong review when the draft is cheap (not kernel, not core_lock, `has_side_effects` is not true, no remaining factual questions).

**Needed:**
1. Weak investigate loop iterates only factual items; operator items stay on the issue.
2. `DEFAULT_WEAK_ROUNDS = 1`. A second round only if the caller passes `maxWeakRounds > 1` and the set shrunk.
3. `ClarifyPolicy.skipStrongReview(view)` true when `!needsKernel && !coreLock && hasSideEffects != true && factual.isEmpty()`.
4. Tests: operator-only leftovers never call `ROLE: investigator` after author; cheap approved-path test now expects `review_verdict: skipped`; a `needsKernel=true` fixture still reaches strong review.
5. No ACP required for Jules. Do not change the no-`weak` path beyond existing skip warnings.

## Investigation
- `ClarifyPolicy.leftoverOrReview` already skips leftover-answers when no factual items.
- `tryClarify` still always calls `reviewWithStrong` if strong != null and ready.

## Important details
- Cheap skip must not apply when `has_side_effects: true` (cross-module impact deserves a review).
- `coreLock` is on `IssueScaffoldResult`, include it in `ClarifyView`.

## Side effects
- ACP `--clarify`: fewer weak/strong calls. Jules path unchanged because it never calls ACP.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest --tests io.mazewall.orchestrator.ClarifyStageTest -PincludeOrchestrator=true`.

<!-- id: issue-20260823-185031  file: issue-20260823-185031-investigate-only-factual-questions-and-skip-cheap-strong-rev.md -->
