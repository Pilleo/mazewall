---
title: "Send only factual leftover questions to strong ACP"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies:
  - "issue-20260823-183303"
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
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Send only factual leftover questions to strong ACP

**Context:**
After weak investigation, every remaining `open_questions` item is sent to strong `ROLE: leftover-answers`. Operator trade-offs (LRU vs FIFO, autonomy, product scope) cannot be answered from the repo. Strong models should only see leftover **factual** items (does this API exist, which ABI does CI run). Judgment items stay on the issue with `open_questions: true`.

**Needed:**
1. Classify each leftover question as `factual` or `operator` with a tiny deterministic heuristic (keywords: `should we`, `or`, `trade-off`, `prefer`, `autonomy`) plus an optional `"kind"` field if the JSON payload includes it.
2. Strong leftover-answers receives only `factual` items. If none, skip that ACP call.
3. Operator items remain in `openQuestionItems` and the issue's open-questions heading.
4. Test: mixed list `["Does Landlock ABI v4 exist on CI?", "LRU or FIFO?"]` → strong prompt contains the ABI question and not `LRU`; issue still lists LRU.
5. Do not invent answers for operator questions.

## Investigation
- `answerLeftoversWithStrong` currently joins all `openQuestionItems`.
- Halt-on-no-shrink (`issue-20260823-183303`) should land first so leftovers are stable.

## Important details
- Heuristic must be unit-tested without ACP.
- When in doubt (no keyword), treat as factual so real blockers still get a strong pass.

## Side effects
- Strong leftover-answers prompt only sees factual items; operator questions stay in the issue.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest -PincludeOrchestrator=true`.

<!-- id: issue-20260823-183312  file: issue-20260823-183312-send-only-factual-leftover-questions-to-strong-acp.md -->
