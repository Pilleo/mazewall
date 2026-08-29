---
title: "Add frontmatter target metadata sync to PaperclipBacklogSync"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "scripts/paperclip_backlog_sync.kts"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogParser.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: ec9ff8fb-2464-45e9-b022-d88ed392123f
paperclip_identifier: MAZ-688
---

# 🟡 [Severity: MEDIUM]: Add frontmatter target metadata sync to PaperclipBacklogSync

**Context:**
`scripts/paperclip_backlog_sync.kts` syncs markdown backlog issues from `docs/internals/backlog/` into Paperclip board issues. While it mirrors title, description, priority, and dependency links, it does not explicitly structure or embed `target_modules` and `target_files` as structured YAML / JSON metadata or clear frontmatter tags in the issue payload. For `HybridSupervisor` and `DispatchSelector` to perform fast, conflict-free scheduling on the board without needing to re-read local files from disk, the sync script must attach `target_modules` and `target_files` in a standardized, easily parseable format.

**Needed:**
1. In `scripts/paperclip_backlog_sync.kts`:
   - Preserve frontmatter `target_modules` and `target_files` in the synced Paperclip issue body or metadata header.
   - Include `target_modules` in the issue description comment or structured marker block (e.g. `<!-- mazewall:targets modules=[...] files=[...] -->`).
2. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogParser.kt` and `PaperclipClient.kt`:
   - Add helper parser methods to read target metadata from `PaperclipIssue.description`.
3. Verify synchronization and parsing with unit tests.
4. Run `./gradlew :tools:orchestrator:test -PincludeOrchestrator=true`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-104517  file: issue-20260827-104517-add-frontmatter-target-metadata-sync-to-paperclipbacklogsync.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
