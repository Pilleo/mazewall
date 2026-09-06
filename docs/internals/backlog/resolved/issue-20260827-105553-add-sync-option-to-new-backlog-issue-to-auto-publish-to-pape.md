---
title: "Add sync option to new_backlog_issue to auto-publish to Paperclip"
severity: "LOW"
status: "resolved"
priority: medium
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "scripts/new_backlog_issue.sh"
  - "scripts/paperclip_backlog_sync.kts"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: 5807af1a-6339-429b-9392-a16041e13cbe
paperclip_identifier: MAZ-691
---

# 🟢 [Severity: LOW]: Add sync option to new_backlog_issue to auto-publish to Paperclip

**Context:**
After creating a new backlog issue file via `./scripts/new_backlog_issue.sh`, developers and orchestrator daemons must currently run a separate manual step (`kotlin scripts/paperclip_backlog_sync.kts`) for the new task to be ingested into the local Paperclip board. Adding an optional `--sync` flag directly to `new_backlog_issue.sh` will streamline workflow velocity by creating the file and syncing it to the active Paperclip company board in a single command.

**Needed:**
1. In `scripts/new_backlog_issue.sh`:
   - Add a `--sync` flag to the bash CLI argument parser.
   - When `--sync` is passed, after successful issue file scaffolding, immediately invoke `kotlin scripts/paperclip_backlog_sync.kts` (or call `PaperclipClient` API directly).
2. Support passing Paperclip configuration / company ID from environment (`PAPERCLIP_COMPANY_ID`, `PAPERCLIP_API_URL`).
3. Verify with `--dry-run` and test execution.
4. Run `./gradlew :tools:orchestrator:test -PincludeOrchestrator=true`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-105553  file: issue-20260827-105553-add-sync-option-to-new-backlog-issue-to-auto-publish-to-pape.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
