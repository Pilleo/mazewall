---
title: "One start-approval gate for hybrid Paperclip + orchestrator loop"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - issue-20260823-181011
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorStates.kt"
  - "scripts/paperclip_telegram_bridge.py"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/TelegramBot.kt"
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: One start-approval gate for hybrid Paperclip + orchestrator loop

**Context:**
Orchestrator `PENDING_APPROVAL` Telegram-gates every coding task before Jules starts. Paperclip has a different approval object (hire/strategy) plus issue-thread `request_confirmation`. The Python bridge already maps `approval.requested` to Telegram buttons. If ingest (`issue-20260823-181011`) starts creating Paperclip issues **and** the daemon still Telegram-gates the same markdown item, the operator will approve twice or approve on one plane while the other proceeds. Dual half-wired gates are how tasks stall (see live MAZ-14 blocked, MAZ-16/17 auth).

**Needed:**
1. Pick **one** start gate for “this backlog item may begin mutating git”:
   - Default: keep orchestrator Telegram `PENDING_APPROVAL` as the start gate for dispatch. After approval, assign/checkout the Paperclip issue (do not also require Paperclip board hire-style approval for ordinary coding tasks).
   - Do not send a second Telegram message from `paperclip_telegram_bridge.py` for the same backlog id when the daemon already asked. Dedup key: `paperclip_issue_id` or markdown issue id.
2. Bridge `approval.requested` remains valid for Paperclip-native events (hire, instruction diffs, `request_confirmation` that are **not** the coding start gate).
3. Document the gate in `tools/orchestrator/README.md` in one paragraph: Paperclip is the board; Telegram start approval lives in the dispatcher until replaced as a whole.
4. Tests: `PENDING_APPROVAL` still blocks dispatch without approval; add a unit test that a Paperclip `approval.requested` with metadata `source=start_gate` is ignored when `SlotContext` already has `approvalRequestSent` (or equivalent dedup flag). If that requires a new fake, keep it in `:tools:orchestrator` tests.

---

**Verification:** `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.StateHandlerTest --tests io.mazewall.orchestrator.AsyncTelegramReviewTest`. Operator sees at most one start-approval prompt per issue.
