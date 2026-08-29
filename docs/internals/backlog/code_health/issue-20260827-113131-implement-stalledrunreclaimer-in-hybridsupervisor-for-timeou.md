---
title: "Implement StalledRunReclaimer in HybridSupervisor for timeout recovery"
severity: "MEDIUM"
status: "open"
priority: high
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
paperclip_issue_id: 410f76ec-e13d-4a4f-b208-a0f18f445d5f
paperclip_identifier: MAZ-694
---

# 🟡 [Severity: MEDIUM]: Implement StalledRunReclaimer in HybridSupervisor for timeout recovery

**Context:**
Currently, when `HybridSupervisor` dispatches an issue to an agent, the issue moves to `in_progress`. If an asynchronous cloud agent (such as Jules or a detached worker) crashes, hangs, or exceeds its SLA without creating a PR or updating its heartbeat/activity log for a long duration (e.g. >30m), the issue remains perpetually stranded in `in_progress`, blocking dependent tasks and tying up dispatch budget. A `StalledRunReclaimer` watchdog in the supervisor loop will inspect active in-progress issues, detect stalled runs without activity, post a diagnostic comment to the Paperclip board, and reset or reassign the issue cleanly.

**Needed:**
1. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/PaperclipClient.kt`:
   - Add helper methods to query active run metadata and last activity timestamp for an issue.
   - Add support for resetting an issue status to `todo` or transitioning to `needs_attention`.
2. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt`:
   - Implement `reclaimStalledRuns(issues: List<PaperclipIssue>, timeout: Duration)` watchdog invoked during `tick()`.
   - Post an explanatory comment on the board when an issue is reclaimed.
3. Add unit tests in `HybridSupervisorTest.kt` simulating stale in-progress runs and verifying clean reclamation and unassignment.
4. Run `./gradlew :tools:orchestrator:test -PincludeOrchestrator=true`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-113131  file: issue-20260827-113131-implement-stalledrunreclaimer-in-hybridsupervisor-for-timeou.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
