---
title: "Host-close factual open questions without ACP"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - "issue-20260823-185025"
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueClarifier.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/IssueTemplateGeneratorTest.kt"
target_symbols:
  - "IssueClarifier"
  - "questionKind"
verify_cheap:
  - "./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Host-close factual open questions without ACP

**Context:**
`--open-question "Does Cache.kt exist?"` and “who calls Cache?” are answerable from `target_files` and the AST hit list. Jules never gets a weak investigator. Leaving those items as open_questions forces a human or a later ACP pass. Operator questions (`LRU or FIFO?`) must stay open. Conservative: only close when a file token exists on disk or a symbol has hits.

**Needed:**
1. `internal fun hostCloseFactual(questions, repoRoot, files, hits): Pair<remaining, details>`.
2. Close a factual question if it names `*.kt`/`*.java` that `File(repoRoot, …).isFile` or is in `files`; detail `path exists`.
3. Close a factual question if it names a scanned symbol that has `hits`; detail first three `ImpactHit.render()` lines.
4. Never close `QuestionKind.OPERATOR`. Never invent ABI/kernel/CI answers.
5. Run this on the no-ACP write path and at the start of `tryClarify` (before weak author) so Jules and ACP see the same remaining questions.
6. Tests without ChatModel: file-exists question disappears; LRU question remains; “Does Landlock ABI v4 exist on CI?” remains.

## Investigation
- `questionKind` already splits factual vs operator.
- Scanner hits are `file:line symbol snippet`.

## Important details
- Prefer false negatives (leave open) over wrong closes.
- Details go to `importantDetails` / Investigation, not into Needed.

## Side effects
- `open_questions` may shrink on write; Jules issues keep only operator leftovers and truly unknown factuals.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest -PincludeOrchestrator=true`.

<!-- id: issue-20260823-185028  file: issue-20260823-185028-host-close-factual-open-questions-without-acp.md -->
