---
title: "Add interactive Telegram clarification prompts for open operator questions in HybridSupervisor"
severity: "LOW"
status: "open"
priority: medium
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/TelegramBot.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/SupervisorTelegramTest.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/HybridSupervisorTest.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: approved
has_side_effects: true
paperclip_issue_id: 3c631e48-0397-4e78-82b3-4587404c7d1d
paperclip_identifier: MAZ-696
---

# 🟢 [Severity: LOW]: Add interactive Telegram clarification prompts for open operator questions in HybridSupervisor

**Context:**
Adding interactive Telegram clarification prompts introduces callback handling in `TelegramBot` that integrates with the existing `pollUpdates` dispatch loop. The change adds `sendClarificationPrompt` and `waitForClarification` to `TelegramBot`, and `requestOperatorClarification` to `HybridSupervisor`.

`pollUpdates` currently handles `approve:`, `skip:`, `pcapprove:`, and `pcreject:` callback prefixes—extending it to route `clarify:<issueId>:<option>` callbacks into a pending clarifications map mirrors the existing approval pattern cleanly.

**Needed:**
1. In `TelegramBot.kt`: Add `pendingClarifications = ConcurrentHashMap<String, String>()` to track `issueId -> selectedOption`.
2. In `TelegramBot.pollUpdates()`: Add handler for `clarify:<issueId>:<option>` callbacks, store option in `pendingClarifications`, answer callback, and clear markup.
3. In `TelegramBot.kt`: Add `sendClarificationPrompt(issueId: String, question: String, options: List<String>): Boolean` with inline keyboard buttons.
4. In `TelegramBot.kt`: Add `waitForClarification(issueId: String, timeoutSeconds: Int = 60): String?`.
5. In `HybridSupervisor.kt`: Add `requestOperatorClarification(issue: PaperclipIssue, question: String, options: List<String>): String?`.
6. In `SupervisorTelegramTest.kt`: Add tests verifying clarification callback routing and prompt markup generation.
7. Run `./gradlew :tools:orchestrator:test` and `./gradlew :tools:orchestrator:checkBacklog`.

## Investigation
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/TelegramBot.kt
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt
- TelegramBot.sendMessageWithApprovalMarkup
- TelegramBot.waitForApproval
- TelegramBot.pollUpdates
- TelegramBot.answerCallbackWith
- HybridSupervisor.tick
- HybridSupervisor.runForever
- HybridSupervisor.telegramBot
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/TelegramBot.kt:111
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/TelegramBot.kt:113-154
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/TelegramBot.kt:122-144
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt:16-26
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt:206-230
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt:355-365
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorEnvironment.kt:21-49
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorEnvironment.kt:116-160
- tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/SupervisorTelegramTest.kt
- tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/HybridSupervisorTest.kt

## Important details
- Fail-closed: requestOperatorClarificationmust return null (not throw orbypass) whentelegramBot is null
- Noblocking of JVM coordinationsyscalls (referto enforcer/AGENTS.md#1-never-block-jvm-coordination-system-calls)
- Reuse existing callback dataprefix pattern: clarify:<issueId>:<option>for consistency withapprove:/skip:/review:/pcapprove:/pcreject
- Timeouthandling:waitForClarificationmust havea configurabletimeout (default 60s) to prevent indefinitedaemonstalls
- Preserve poll-based architecture: no new backgroundthreads;allwaitingmust integratewith existing pollUpdatesloop
- CallbackACK:clarificationselectionsmust triggeranswerCallbackWith to provideuser feedback inthe Telegram client
- pollUpdates mustbe modifiedtohandle clarify:prefixcallbacks,notjust addedasnew isolatedmethods
- pendingClarificationsmust useConcurrentHashMap forthread safety,matching pendingApprovals pattern
- callback_data format mustbe clarify:<issueId>:<option> toallowroutingand optionextraction
- sendClarificationPromptmust answerCallbackWith andclearReplyMarkupafter selection,like paperclip approvals
- waitForClarification must haveconfigurabletimeout to prevent daemonstalls (default 60s)
- requestOperatorClarification must return nullwhen telegramBot isnull (fail-closed,no bypass)
- Nochangesrequiredto OrchestratorEnvironment interface;HybridSupervisor usesTelegramBot directly
- OrchestratorDaemondoes not needmodificationas it only constructsTelegramBot andpasses to environment

## Side effects
- New publicAPIonTelegramBot: sendClarificationPrompt,waitForClarification
- New public APIon HybridSupervisor: requestOperatorClarification
- New callback dataformat: clarify:<issueId>:<option>
- Potential newtestfiles:TelegramBotTest.kt, HybridSupervisorTest.kt (ifnot existing)
- TelegramBot.pollUpdates() callbackroutingextendedwithnewclarify:prefix handler
- TelegramBot gainsnew private state:pendingClarificationsmap
- TelegramBot gainsnew public API: sendClarificationPrompt, waitForClarification
- HybridSupervisor gainsnew public API:requestOperatorClarification
- SupervisorTelegramTest.kt requiresnew testsfor clarification callback routing
- HybridSupervisorTest.kt requires new testsfor requestOperatorClarification integration

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-113352  file: issue-20260827-113352-add-interactive-telegram-clarification-prompts-for-open-oper.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
