---
title: "Scan impact and outlines before first ACP author"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueClarifier.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/AstImpactScanner.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/IssueTemplateGeneratorTest.kt"
target_symbols:
  - "IssueClarifier"
  - "FilesystemImpactScanner"
verify_cheap:
  - "./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Scan impact and outlines before first ACP author

**Context:**
`tryClarify` currently calls weak ACP `ROLE: author` with up to eight 4k file excerpts, then scans identifiers, then may call `ROLE: side-effects`. The first (most expensive) model call is the least informed. Deterministic work (symbol names, `FilesystemImpactScanner` hits, file outlines) does not need a model. File bodies should not be dumped into the prompt; ACP can `fs/read_text_file` if it needs a span. `issue-20260823-181021` (Codanna work-package JSON) is a later consumer, not a blocker: use the existing scanner.

**Needed:**
1. In `tryClarify`, run `impactScanner.scan` and collect per-target-file outlines (Kotlin: `class`/`object`/`fun` names only, cap ~40 lines per file) **before** `authorWithWeak`.
2. Attach hits via `withImpactHits`. Put outlines + hit list into the author prompt instead of `excerpts()`.
3. Auto-write concrete hits into `## Side effects`. Call `investigateSideEffectsWithWeak` only when `has_side_effects==true` and (hits empty or contradictory, e.g. declared false but hits nonempty).
4. Remove `excerpts()` from author / investigate / side-effects / review-fix prompts. Mention readable paths; do not paste bodies.
5. Tests: AST hits appear in the first weak prompt; `ROLE: side-effects` is not called when hits already name the external file; `excerpts` `### path` file dumps are absent from that prompt.
6. Do not add dependencies. Do not dump Codanna graphs.

## Investigation
- `tryClarify` order: author → scan → maybe side-effects → investigate while questions remain.
- `excerpts()` takes 4000 chars × 8 files plus three design-doc paths.
- `FilesystemImpactScanner` already returns compact `file:line symbol snippet` hits.

## Important details
- Origin files are omitted from hits (external impact only).
- Outline helper stays in `:tools:orchestrator` (no new Gradle deps).
- vibe-acp may echo example JSON `"context":"..."`; prompts should not use `"..."` as a schema dummy (use `""` / `[]`).

## Side effects
- Author/side-effects/investigate prompts and `IssueTemplateGeneratorTest` role-order assertions change.
- Tests that expect `ROLE: side-effects` after every `has_side_effects:true` author must be updated.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest -PincludeOrchestrator=true`.

<!-- id: issue-20260823-183258  file: issue-20260823-183258-scan-impact-and-outlines-before-first-acp-author.md -->
