---
title: "Add target-based conflict checking to HybridSupervisor"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/DispatchSelector.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: 2ebf4447-c16f-46c8-a30c-1a2dacfeffe5
paperclip_identifier: MAZ-687
---

# 🟡 [Severity: MEDIUM]: Add target-based conflict checking to HybridSupervisor

**Context:**
The standalone `OrchestratorDaemon` performs conflict-free scheduling by inspecting `target_files` and `target_modules` across all currently active slots to prevent two concurrent tasks from modifying overlapping files or Gradle subprojects in parallel. However, `HybridSupervisor` and `DispatchSelector` in the Paperclip hybrid setup currently only check explicit DAG blockers (`issue.blockedBy.all { it.status in ["done", "cancelled"] }`). When multiple tasks are dispatchable, the supervisor dispatches them concurrently without checking whether their `target_files` or `target_modules` overlap with already running tasks (`in_progress` issues on the Paperclip board), creating git merge collisions and parallel test contention.

**Needed:**
1. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/DispatchSelector.kt`, add target overlap inspection:
   - Extract `targetFiles` and `targetModules` from `PaperclipIssue` (parsing frontmatter or description metadata).
   - In `ordered()` and `select()`, accept the list of currently active issues (`in_progress` / `in_review`) and filter out candidate backlog issues that share any `target_files` or `target_modules` with active issues.
   - Treat tasks with empty targets conservatively as global locks unless explicitly marked non-interfering.
2. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt`, pass active tasks into `DispatchSelector.select(fresh, activeIssues, forceIdentifier)`.
3. Add unit tests in `tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/HybridSupervisorTest.kt` and `DispatchSelectorTest.kt` verifying that overlapping issues are excluded from dispatch while disjoint issues run in parallel.
4. Run `./gradlew :tools:orchestrator:test -PincludeOrchestrator=true`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-104508  file: issue-20260827-104508-add-target-based-conflict-checking-to-hybridsupervisor.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
