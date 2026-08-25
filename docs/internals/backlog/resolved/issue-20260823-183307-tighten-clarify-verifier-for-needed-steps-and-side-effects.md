---
title: "Tighten clarify verifier for needed steps and side effects"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueClarifier.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/IssueTemplateGeneratorTest.kt"
target_symbols:
  - "IssueMarkdownVerifier"
verify_cheap:
  - "./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Tighten clarify verifier for needed steps and side effects

**Context:**
`IssueMarkdownVerifier.readyForReview` only rejects blank/FILL Context and Needed. A vibe-acp author that echoed the schema dummy `"context":"..."` was treated as ready and then `review_verdict: approved`. That ships unusable issues. Ready must also require numbered Needed steps, an explicit `has_side_effects` true/false, and a non-empty `## Side effects` (or YAML impacts) when true. Ellipsis-only bodies (`...`) are FILL.

**Needed:**
1. Treat context/needed as FILL if they are blank, contain `FILL:`, or are only `.` / `...` after trim.
2. Needed must contain at least one numbered step (`1.` or `1)`).
3. After author, `has_side_effects` must be non-null. If true, `sideEffectImpacts` must be non-empty (hits or model lines).
4. Extra files listed in the payload that are not under `repoRoot` are not this verifier's job; existence checks stay out unless cheap.
5. Tests: `"..."` context fails readyForReview; Needed without `1.` fails; `has_side_effects:true` without impacts fails; existing clarify mocks that would fail must be updated so the suite stays green.
6. Schema examples in ACP prompts must use `""` and `[]`, not `"..."`.

## Investigation
- First `--clarify` with vibe-acp 2026-08-23 produced Context/Needed `...` and `review: approved`.
- `parseJsonStringField` takes the first `"context"` string in the model output; echoed schema wins.

## Important details
- `structural` stays title/files/modules/component only.
- Strong review is skipped when not ready (already implemented).

## Side effects
- `readyForReview` failures skip strong review.
- Existing mocks must emit numbered Needed and `has_side_effects`.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest -PincludeOrchestrator=true`.

<!-- id: issue-20260823-183307  file: issue-20260823-183307-tighten-clarify-verifier-for-needed-steps-and-side-effects.md -->
