---
title: "Route open operator trade-offs to Paperclip Decision Queues in HybridSupervisor"
severity: "LOW"
status: "resolved"
priority: medium
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/PaperclipClient.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: approved
has_side_effects: true
paperclip_issue_id: c7944713-04e1-4bcf-bfb0-31a6762e2a5e
paperclip_identifier: MAZ-736
---

# 🟢 [Severity: LOW]: Route open operator trade-offs to Paperclip Decision Queues in HybridSupervisor

**Context:**
Currently, when `IssueClarifier` or `HybridSupervisor` detects genuine operator trade-offs (e.g. non-default adapter unlock, breaking API changes, or architectural policy choices), decisions are either logged as errors or handled through unstructured channels.

Paperclip has a first-class **Decision Queues** subsystem (`POST /api/decision-queues` and `POST /api/approvals`) that integrates with Telegram (`pcapprove:` / `pcreject:` callbacks). Routing open operator trade-offs to Paperclip Decision Queues provides formal human-in-the-loop governance while preserving deterministic, fail-closed execution.

**Needed:**
1. In `HybridSupervisor.kt`: Add `requestOperatorDecision(prompt: String, context: Map<String, String>): String?` method that registers a Paperclip approval and returns the `approvalId`.
2. In `HybridSupervisor.kt`: Route unapproved adapter requests or ambiguous policy choices to Decision Queues rather than hard failing.
3. In `HybridSupervisor.kt`: Integrate decision polling with existing `pollApprovals()` and Telegram callbacks.
4. Add unit tests in `HybridSupervisorTest.kt` verifying Decision Queue creation on operator policy questions and clean continuation upon approval.
5. Run `./gradlew :tools:orchestrator:test` and `./gradlew :tools:orchestrator:checkBacklog`.

## Investigation
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/PaperclipClient.kt
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/EventNotifier.kt
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/TelegramBot.kt
- tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueClarifier.kt
- HybridSupervisor.tick
- HybridSupervisor.runForever
- PaperclipClient.listPendingApprovals
- PaperclipClient.decideApproval
- EventNotifier.pollApprovals
- TelegramBot.onPaperclipApproval
- QuestionKind.OPERATOR classificationheuristic

## Important details
- Fail-closed invariant: requestOperatorDecision must returnnull (not throwor bypass) whenPaperclipClient or EventNotifier is unavailable
- Noblockingof JVM coordination syscalls (enforcer/AGENTS.md u00a71)
- Preserve poll-based architecture: no new backgroundthreads; all waitingmust integrate with existingtickloopandpollUpdates
- Timeout handling: decisionpollingmust havea configurable timeout (default 60s) to preventindefinitedaemon stalls
- CallbackACK:decisionselectionsmust triggeranswerCallbackWith toprovide user feedback inTelegramclient
- Reuse existing approvalpattern: approvalIdroutingmirrorspcapprove/pcreject callbackdataformat
- Deterministic planner: no ACP foroperatortrade-offs; humandecision viaPaperclip isthesinglesource of truth
- State consistency: pendingOperatorDecisions mapmust useconcurrentcollections forthread safety

## Side effects
- New public API onHybridSupervisor:requestOperatorDecision,checkOperatorDecision
- New internalstate on HybridSupervisor: pendingOperatorDecisions map
- Behavioralchange: unapproved adapter types nowcreate Paperclipapprovals instead ofimmediaterefusal
- TelegramBot gainsnew callbackdata formatforoperatordecisions(extensionofpcapprove/pcreject pattern)
- EventNotifier willannounceoperatordecisionsvia existingpollApprovals mechanism

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-120741  file: issue-20260827-120741-route-open-operator-trade-offs-to-paperclip-decision-queues.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
