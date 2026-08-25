---
title: "Do not resolve markdown from Paperclip done without orchestrator ResolveTask"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20260823-181015"
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "scripts/paperclip_telegram_bridge.py"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true
paperclip_issue_id: 1658732d-f67c-492b-ac80-c91b0ecd9d81
---

# 🟡 [Severity: MEDIUM]: Do not resolve markdown from Paperclip done without orchestrator ResolveTask

**Context:**
`paperclip_telegram_bridge.py` `sync_git_lifecycle` runs when Paperclip status is `done`. It guesses `backlogFile` via glob on MAZ ids and can move markdown to `resolved/` (and historically `git pull --rebase` on the operator tree — `181015`). Paperclip `done` is not CI-green + merged. The only path that should set markdown `status: resolved` and move the file is orchestrator `ResolveTaskState` (or a future dispatcher with the same tests).

**Needed:**
1. On Paperclip `done`, the bridge must **not** move or rewrite backlog markdown. Log and Telegram-notify only.
2. Optional later (not this issue): if `metadata.backlogFile` is set **and** orchestrator already marked the same id resolved, no-op. Do not invent a second resolver.
3. Keep `181015` constraints: no `git pull --rebase` on the operator working tree from this script.
4. Test: a fixture function or scripted check that `sync_git_lifecycle` does not call `shutil.move` / write frontmatter `resolved` (extract the move into a function and unit-test with a fake, or document a pytest if Python tests exist; otherwise a small Kotlin test is not required — a grep-based comment in the PR is not enough; prefer extracting `shouldResolveMarkdown(event) -> false` and testing that).
5. Do not change orchestrator `MarkIssueAsResolved`.

## Investigation
- Bridge `sync_git_lifecycle` around line 49; glob fallback on `MAZ-` ids.
- `ResolveTaskState` in `OrchestratorStates.kt` is the working resolver.

## Side effects
- Bridge currently can mark work resolved while CI/merge never ran.

---

**Verification:** Bridge no longer moves `docs/internals/backlog/**/*.md` on Paperclip `done`. `./gradlew :tools:orchestrator:checkBacklog -PincludeOrchestrator=true`.

<!-- id: issue-20260823-212912  file: issue-20260823-212912-do-not-resolve-markdown-from-paperclip-done-without-orchestr.md -->
