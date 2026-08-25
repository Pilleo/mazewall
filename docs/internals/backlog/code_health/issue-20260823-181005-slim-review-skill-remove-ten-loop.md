---
title: "Slim review skill: scoped diff review, drop 10-loop and no-summarize rules"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "docs"
target_modules:
  - ":tools:orchestrator"
target_files:
  - ".agents/skills/review/SKILL.md"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorPrompts.kt"
effort: "small"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: e63aebc3-c305-401f-b1b2-5917ac8dbda1
---

# 🟡 [Severity: MEDIUM]: Slim review skill: scoped diff review, drop 10-loop and no-summarize rules

**Context:**
`.agents/skills/review/SKILL.md` requires regenerating class diagrams and knowledge maps, then repeating a hypothesis loop at least 10 times, and forbids summarizing. Orchestrator idle-queue review campaigns (`OrchestratorPrompts.REVIEW_SKILL_HEADER`) point Jules at this skill. That is how the backlog grows faster than it drains (91 open markdown issues). The skill also hijacks a human “please review this” request into a never-ending audit. Security review still matters; unbounded process theater does not.

**Needed:**
1. Rewrite the review skill into two explicit modes in the same file:
   - **PR/diff review (default):** read the diff (or named module), check fail-closed / FFM / JVM-floor / tests, post a structured verdict. Stop when the diff is covered. No 10-loop, no “do not summarize”, no mandatory diagram regeneration.
   - **Campaign audit (opt-in):** only when the user or orchestrator says “campaign” / “module audit”. Cap new backlog issues (e.g. max 5 atomic issues per run). Dedup against existing open issues via knowledge maps *if they already exist*; do not `./gradlew generateKnowledgeMap` as a required first step.
2. Delete: “repeat Phase 1–3 at least 10 times”, “do not prematurely summarize”, “if you have not logged a finding in 2 turns you must dig deeper”.
3. Keep: fail-closed, FFM ABI, no silent EPERM swallow, do not large-refactor during review, create backlog issues with `create_backlog_issue` for real findings.
4. Update `OrchestratorPrompts.reviewTaskIssueBody` / `REVIEW_SKILL_HEADER` to request **PR/diff review** (or campaign with a cap), not the old 10-loop text.
5. Add/adjust `OrchestratorPromptsTest` so the injected review prompt no longer contains “10 times” or “do not summarize”.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.OrchestratorPromptsTest`. Skill file has no 10-loop / anti-summary termination rules.
