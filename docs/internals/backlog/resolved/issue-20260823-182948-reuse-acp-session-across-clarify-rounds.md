---
title: "Reuse ACP session across clarify rounds"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/AcpChatModel.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/IssueTemplateGeneratorTest.kt"
target_symbols:
  - "AcpChatModel"
  - "AcpJsonRpcSession"
verify_cheap:
  - "./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Reuse ACP session across clarify rounds

**Context:**
`AcpChatModel.complete` starts a new ACP process, `initialize`s, opens `session/new`, sends one prompt, then `destroyForcibly`. `--clarify` calls `complete` for author, side-effects, up to three investigate rounds, review-fix, plus a separate strong process for leftovers and review. Each round pays model load (~seconds to tens of seconds) and a 90s timeout. Weak models (vibe-acp / Mistral) are the intended workers; killing them every turn makes the loop too slow to use. Writes and terminal must stay rejected. `fs/read_text_file` under the repo cwd must keep working on the reused session.

**Needed:**
1. Keep one ACP process + session per `AcpChatModel` instance (weak vs strong are still two instances).
2. `complete` sends `session/prompt` on the existing session; do not `initialize`/`session/new` again.
3. Close the process in `AutoCloseable.close()` / `use {}` from `NewBacklogIssue` after `tryClarify` returns (success or skip).
4. Timeouts still apply per prompt, not for the whole clarify run as a single 90s budget.
5. Tests: a fake stdio agent sees `initialize` and `session/new` once across two `complete` calls; a write/`terminal/` request on the second prompt is still JSON-RPC error; `fs/read_text_file` under cwd still returns content.
6. Do not allow writes. Do not share one process between weak and strong.

## Investigation
- Read `AcpChatModel.complete` (process spawn, 90s `future.get`, `destroyForcibly`).
- `IssueClarifier.tryClarify` calls `ChatModel.complete` once per role step.
- vibe-acp 2.24.2 speaks ACP `initialize` / `session/new` / `session/prompt`; default mode is `accept-edits` (writes must still be denied by our client).

## Important details
- Weak and strong stay separate processes so review is not the same session that authored.
- `session/update` from vibe can arrive before `session/new` result; the waiter already skips notifications.
- Per-prompt timeout remains `DEFAULT_TIMEOUT_MS`; do not let a hung second prompt pin the process forever without destroy.

## Side effects
- `IssueClarifier.tryClarify` and `NewBacklogIssue` must close the models.
- `IssueTemplateGeneratorTest` ACP fake must allow two prompts on one session.
- Clarify latency drops; failure mode changes from “one hung spawn” to “hung reused session” — destroy on close/timeout still required.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.IssueTemplateGeneratorTest` with `-PincludeOrchestrator=true`.

<!-- id: issue-20260823-182948  file: issue-20260823-182948-reuse-acp-session-across-clarify-rounds.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
