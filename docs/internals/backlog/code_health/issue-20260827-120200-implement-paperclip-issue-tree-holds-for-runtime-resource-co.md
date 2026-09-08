---
title: "Implement Paperclip Issue Tree Holds for runtime resource conflict locking in HybridSupervisor"
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
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/DispatchSelector.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: skipped
paperclip_issue_id: de1b8b1b-7dec-4df5-adea-34b639a8a071
paperclip_identifier: MAZ-697
---

# 🟡 [Severity: MEDIUM]: Implement Paperclip Issue Tree Holds for runtime resource conflict locking in HybridSupervisor

**Context:**
Currently, tasks with overlapping target files or modules that lack a causal dependency compete during dispatch. Rather than creating synthetic dependency links or purely hiding tasks in local memory, Paperclip provides a dedicated first-class subsystem for **Issue Tree Holds** (`POST /api/issues/:id/tree-holds` and `POST /api/issues/:id/tree-holds/release`).

When `HybridSupervisor` dispatches an issue (e.g. `MAZ-101`), it can place conflicting candidate issues on an active Tree Hold with `reason: "Resource lock held on [target_files] by active run MAZ-101"`. This provides clear visibility on the Paperclip board UI while cleanly preventing conflicting concurrent dispatches. When `MAZ-101` reaches `done` or `cancelled`, the supervisor releases the hold.

**Needed:**
1. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/PaperclipClient.kt`:
   - Add `createTreeHold(issueId: String, reason: String, mode: String = "hold"): String` calling `POST /api/issues/:id/tree-holds`.
   - Add `releaseTreeHold(holdId: String, releaseReason: String): Boolean` calling `POST /api/issue-tree-holds/:id/release`.
   - Add `listActiveHolds(companyId: String): List<PaperclipHold>`.
2. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/DispatchSelector.kt`:
   - Compute `activeResourceLocks` across all `in_progress` issues.
   - Filter out candidate issues whose targets intersect active locks.
3. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt`:
   - On dispatch of issue $A$, acquire Tree Holds for any conflicting backlog issues.
   - In `resolver.resolveIfNeeded()` / tick cleanup, release Tree Holds when issue $A$ resolves.
4. Add unit tests in `HybridSupervisorTest.kt` verifying Tree Hold creation on conflict and clean release on resolution.
5. Run `./gradlew :tools:orchestrator:test` and `./gradlew :tools:orchestrator:checkBacklog`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-120200  file: issue-20260827-120200-implement-paperclip-issue-tree-holds-for-runtime-resource-co.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
