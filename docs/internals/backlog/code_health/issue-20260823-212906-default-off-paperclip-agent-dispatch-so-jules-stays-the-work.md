---
title: "Default-off Paperclip agent dispatch so Jules stays the worker"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20260823-181011"
  - "issue-20260823-181013"
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/README.md"
  - "scripts/paperclip_backlog_sync.kts"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/OrchestratorDaemon.kt"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Default-off Paperclip agent dispatch so Jules stays the worker

**Context:**
After ingest (`issue-20260823-181011`) POSTs markdown issues to Paperclip, a heartbeat adapter (agy/vibe/hermes) can start coding on the same backlog id that the daemon is sending to Jules. Two workers on one tree (or even two worktrees without CORE/file locks) is how you get silent dual mutation. Jules remains the default worker via orchestrator. Paperclip adapters stay unassigned unless the operator opts in.

**Needed:**
1. Ingest POST (or its follow-up in this issue if 181011 already POSTs) must **not** set a Paperclip agent/checkout/assignment that starts a run. Create the card as a mirror only (`metadata.backlogId`, `backlogFile`, `targetFiles`).
2. Document env `PAPERCLIP_DISPATCH=off` (default). When `on`, still do not start an adapter from sync; operator assigns in the UI.
3. README: one paragraph — Jules is started by the daemon; assigning a Paperclip agent on the same `paperclip_issue_id` is opt-in and unsupported in parallel with an active orchestrator slot.
4. Tests: if sync grows a “create issue” request builder, assert the payload has no agent assignment / no auto-run field. If this issue is docs-only because 181011 has not landed a builder yet, assert the README paragraph exists via a string test or skip tests and keep the issue `effort: small`.
5. Do not implement Bernstein/Agent OS. Do not replace Jules in this issue.

## Investigation
- Orchestrator `TriggerJulesSession` is the working worker start.
- Paperclip company `mazewall` on `:3100` heartbeats adapters independently of the DAG.

## Side effects
- Ingest must not auto-assign a Paperclip adapter or Jules and an ACP agent will both mutate git.

---

**Verification:** `./gradlew :tools:orchestrator:test -PincludeOrchestrator=true` if a payload test exists; otherwise `checkBacklog`. Sync/docs never imply a default Paperclip coding run.

<!-- id: issue-20260823-212906  file: issue-20260823-212906-default-off-paperclip-agent-dispatch-so-jules-stays-the-work.md -->
