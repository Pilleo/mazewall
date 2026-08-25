---
title: "Document run_orchestrator as the only production loop"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20260823-181014"
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/README.md"
  - "plan.md"
  - "scripts/run_orchestrator.sh"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: false
paperclip_issue_id: 3ccee6cc-9b64-462d-8f86-732326c6feb9
---

# 🟡 [Severity: MEDIUM]: Document run_orchestrator as the only production loop

**Context:**
The operator-facing loop that works is `./scripts/run_orchestrator.sh` (markdown DAG → start approval → GitHub → Jules → PR → CI → review → merge → `resolved/`). `plan.md` still describes a Hybrid Paperclip Architecture as if that were the running control plane. Paperclip ingest prints `TODO: Sync to Paperclip API`. Operators cannot “start the hybrid loop”; they can only open `:3100` and assign a card by hand. That is not equivalent. Until ingest + CI/merge replacement exist, the README and plan must say the Kotlin daemon is production and Paperclip is an optional board.

**Needed:**
1. In `tools/orchestrator/README.md`, add a short **How to run work** section: production command is `./scripts/run_orchestrator.sh` (script already passes `-PincludeOrchestrator=true`). Prerequisites: `gh`, `jules`, optional Telegram. Paperclip UI is not a dispatcher.
2. In `plan.md` Overview, state that Phases 1–5 are a target split (board vs GitHub/CI/merge), not the current runtime. Point to `run_orchestrator.sh` as current.
3. Do not crontab `paperclip_backlog_sync.kts`. Document that explicitly (one sentence).
4. Do not rewrite `OrchestratorStates.kt` in this issue (`issue-20260823-181014` already forbids deleting CI/merge states).
5. No new tests required beyond `./gradlew :tools:orchestrator:checkBacklog -PincludeOrchestrator=true`.

## Investigation
- `scripts/paperclip_backlog_sync.kts` line ~394: `TODO: Sync to Paperclip API`.
- `run_orchestrator.sh` already includes `-PincludeOrchestrator=true`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog -PincludeOrchestrator=true`. README contains `run_orchestrator.sh` as the production trigger; `plan.md` does not claim Paperclip currently owns Jules/CI/merge.

<!-- id: issue-20260823-212901  file: issue-20260823-212901-document-run-orchestrator-as-the-only-production-loop.md -->
