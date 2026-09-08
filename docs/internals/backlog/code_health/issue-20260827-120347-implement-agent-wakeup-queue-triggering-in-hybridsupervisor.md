---
title: "Implement Agent Wakeup Queue triggering in HybridSupervisor on issue unblock"
severity: "LOW"
status: "open"
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
review_verdict: skipped
paperclip_issue_id: 86a794c5-a664-45e5-8bef-18f396fb495b
paperclip_identifier: MAZ-698
---

# 🟢 [Severity: LOW]: Implement Agent Wakeup Queue triggering in HybridSupervisor on issue unblock

**Context:**
Currently, when an issue is unblocked (e.g. its blockers resolve or a conflicting Tree Hold releases), workers discover the newly dispatchable task only when their next poll loop executes. Paperclip has an asynchronous **Agent Wakeup Queue** (`POST /api/agents/:id/wakeup` and `agent_wakeup_requests` table) supporting coalesced wakeups with idempotency keys.

Wiring `HybridSupervisor` to post a wakeup request immediately upon assigning or unblocking a task reduces worker response latency from minutes to sub-second.

**Needed:**
1. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/PaperclipClient.kt`:
   - Add `requestAgentWakeup(agentId: String, reason: String, issueId: String? = null): Boolean` calling `POST /api/agents/:id/wakeup`.
2. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt`:
   - After `client.startProgress(candidate.id)` and `client.assignAgent(candidate.id, agent.id)`, trigger `client.requestAgentWakeup(agent.id, "Dispatch ${candidate.identifier}", candidate.id)`.
   - When resolving an issue, wake the agents of newly unblocked dependent tasks.
3. Add unit tests in `HybridSupervisorTest.kt` verifying agent wakeup triggers on dispatch and resolution.
4. Run `./gradlew :tools:orchestrator:test` and `./gradlew :tools:orchestrator:checkBacklog`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-120347  file: issue-20260827-120347-implement-agent-wakeup-queue-triggering-in-hybridsupervisor.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
