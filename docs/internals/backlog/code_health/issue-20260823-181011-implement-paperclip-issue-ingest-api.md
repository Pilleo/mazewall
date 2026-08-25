---
title: "Implement real Paperclip issue ingest from the markdown DAG"
severity: "HIGH"
status: "open"
priority: high
dependencies:
  - issue-20260823-181010
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HttpTransport.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/HttpTransportTest.kt"
  - "tools/orchestrator/README.md"
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🔴 [Severity: HIGH]: Implement real Paperclip issue ingest from the markdown DAG

**Context:**
Live Paperclip (`127.0.0.1:3100`, company `mazewall`) has 27 issues; the git backlog has 91 open markdown files. `paperclip_backlog_sync.kts` currently prints `TODO: Sync to Paperclip API` and never POSTs. MAZ-22 is marked `done` in Paperclip while ingest is unfinished. Hybrid architecture (`plan.md`) requires markdown as the DAG source and Paperclip as the board. Without POST + `paperclip_issue_id` + `blockedBy`, Paperclip cannot schedule mazewall work and the two boards diverge.

**Needed:**
1. After `issue-20260823-181010`, implement HTTP ingest against Paperclip:
   - Resolve company id (`PAPERCLIP_COMPANY_ID` or `GET /api/companies`).
   - For each unblocked (or `--force` all open) markdown issue lacking `paperclip_issue_id`: `POST /api/companies/:id/issues` with title, description (full markdown body), priority mapping (`high`→`high`, etc.), metadata `{ backlogId, backlogFile, targetFiles, targetModules, component }`.
   - Map `dependencies: []` to Paperclip `blockedByIssueIds` when the dependency already has `paperclip_issue_id`; skip/queue if not yet synced (topological order).
   - Write `paperclip_issue_id: abec8d82-aa58-4915-a074-f961d8ab381b
2. Idempotency: if frontmatter already has `paperclip_issue_id`, skip unless `--force` (then PATCH description/blockers, do not create duplicates).
3. Auth: `Authorization: Bearer $PAPERCLIP_API_KEY`. Fail closed if key missing (already the script behavior). Never log the key.
4. Tests with the existing fake `HttpTransport`: fixture backlog of 2 issues (A depends on B); assert POST order B then A; assert second run POSTs nothing; assert frontmatter updated. Do not hit the live 3100 instance from unit tests.
5. Document the Gradle command in `tools/orchestrator/README.md` (one paragraph). Do not claim MAZ-22 is complete until this lands.

---

**Verification:** `./gradlew :tools:orchestrator:test` including the new ingest tests. Dry-run against a fixture does not require a live server. Manual operator dry-run against 3100 is optional and must use `--dry-run` first.
