---
title: "Stop investigate loop when questions do not shrink"
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

# 🟡 [Severity: MEDIUM]: Stop investigate loop when questions do not shrink

**Context:**
`tryClarify` runs `while (openQuestionItems.isNotEmpty() && round < maxWeakRounds)` and will spend all three weak rounds if the model repeats the same questions. That is wasted ACP cost (especially once sessions are reused, still token cost). A round that does not shrink the question set, and does not move a question into `important_details`, is not progress.

**Needed:**
1. After each successful investigate parse, compare the new `openQuestionItems` set to the previous set (trim, case-sensitive).
2. If the set is equal or a superset (no item removed), stop the loop even if `round < maxWeakRounds`.
3. If verification fails, keep the previous draft and stop (already `break` on exception; also stop on no-shrink).
4. Test: weak investigate returns the same `open_questions` twice → `complete` called once for investigate (plus author), not three times; leftover list is that same question.
5. Do not call strong leftover inside this issue.

## Investigation
- Loop at `IssueClarifier.tryClarify` after the side-effect block.
- `weakInvestigateClosesQuestionsWithoutStrongAnswers` already covers the shrink-to-empty path.

## Important details
- Normalize by trim only; do not fuzzy-match rephrasings (weak models reword endlessly).
- Empty list is shrink → loop ends naturally.

## Side effects
- `tryClarify` investigate while-loop exit conditions change.
- Tests that expected three identical rounds (none today) would fail; add an explicit no-progress test.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest -PincludeOrchestrator=true`.

<!-- id: issue-20260823-183303  file: issue-20260823-183303-stop-investigate-loop-when-questions-do-not-shrink.md -->
