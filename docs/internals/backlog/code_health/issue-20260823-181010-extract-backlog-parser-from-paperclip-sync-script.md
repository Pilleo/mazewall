---
title: "Stop inlining BacklogParser; run Paperclip ingest as an orchestrator main"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "scripts/paperclip_backlog_sync.kts"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BacklogParser.kt"
  - "tools/orchestrator/build.gradle.kts"
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Stop inlining BacklogParser; run Paperclip ingest as an orchestrator main

**Context:**
`scripts/paperclip_backlog_sync.kts` copies `BacklogParser` and a reduced `DependencyGraph` (~250 lines inlined). `plan.md` Phase 1 said to reuse `:tools:orchestrator`. The inlined graph **drops** file/module conflict checks used by `OrchestratorDaemon.selectAndStartTasks`. The Kotlin script is also awkward on Kotlin 2.x (`-Xuse-fir-lt=false`). Do **not** delete `BacklogParser` — it is the only implementation of the markdown DAG (`target_files`, `target_modules`, `dependencies`, `autonomy`). Paperclip’s issue JSON does not have those fields. Two copies will drift (already have).

**Needed:**
1. Keep `BacklogParser.kt` / `DependencyGraph.kt` / `BacklogPriority.kt` as the single library.
2. Add an orchestrator entry point (e.g. `PaperclipBacklogSyncKt` / Gradle `runPaperclipSync`) that uses those classes. Wire `tools/orchestrator/build.gradle.kts` `application` extra main or a dedicated `JavaExec` task.
3. Replace `scripts/paperclip_backlog_sync.kts` with a thin wrapper that calls the Gradle task (or delete the kts and document `./gradlew :tools:orchestrator:runPaperclipSync`).
4. The new main may still be `--dry-run` only in this issue (no HTTP POST yet; that is `issue-20260823-181011`). It must print the same unblocked-issue selection as the daemon’s DAG (not the reduced inlined graph).
5. Tests: existing `BacklogParserEnhancedTest` / `DependencyGraphTest` remain the parser tests. Add a test that the sync entry point selects the same next issue as `DependencyGraph.selectNextIssue` on a fixture tree.
6. Do not change Paperclip API contracts here.

---

**Verification:** `./gradlew :tools:orchestrator:test`. Repo no longer contains a second `data class BacklogIssue` in `scripts/`. `rg "inlined from :tools:orchestrator" scripts/` is empty.
