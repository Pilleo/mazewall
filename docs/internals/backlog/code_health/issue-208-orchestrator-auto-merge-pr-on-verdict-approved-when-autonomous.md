---
title: "Orchestrator: Auto-Merge PR on VERDICT: APPROVED When autonomy=autonomous and solution_approved=true"
severity: "MEDIUM"
status: "open"
priority: 5
dependencies: ["issue-202", "issue-203"]
component: "orchestrator"
effort: "medium"
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🟡 [Severity: MEDIUM]: Orchestrator: Auto-Merge PR on VERDICT: APPROVED When autonomy=autonomous and solution_approved=true

**Context:**
`AWAITING_MERGE` currently polls indefinitely for a human to merge the PR manually. The `autonomy` and `solution_approved` fields are already parsed by `BacklogParser` (since the schema was extended) but are never consumed by the orchestrator. When Jules's review comment contains `VERDICT: APPROVED` (now emitted by the rewritten `AWAITING_REVIEW` prompt), all the information needed to auto-merge safely is present: the solution approach was pre-approved by a human, CI passed, and Jules reviewed the implementation as correct. Requiring manual merge in this case adds unnecessary latency and defeats the goal of a fully autonomous loop.

**Needed:**
1. In `AWAITING_MERGE.execute()`, after confirming CI is `SUCCESS` and the SHA is unchanged, check:
   ```kotlin
   val issue = env.parseAllIssues().firstOrNull { it.id == context.currentIssueId }
   if (issue?.autonomy == "autonomous" && issue.solutionApproved == true) {
       val julesVerdict = env.getJulesReviewVerdict(prNumber)
       if (julesVerdict == "APPROVED") {
           env.println("🤖 Auto-merging PR #$prNumber (autonomy=autonomous, VERDICT: APPROVED)")
           env.sendNotification("🤖 Auto-merging PR #$prNumber (task ${issue.id}, VERDICT: APPROVED)")
           env.mergePr(prNumber)
           return RESOLVE_TASK
       }
   }
   ```
2. Add `fun getJulesReviewVerdict(prNumber: String): String?` to `OrchestratorEnvironment`: scans PR comments for the most recent Jules comment containing `VERDICT: APPROVED/NEEDS_CHANGES/UNCERTAIN` and returns the verdict string, or `null` if none found.
3. Add `fun mergePr(prNumber: String)` to `OrchestratorEnvironment`: implemented as `gh pr merge {prNumber} --squash --auto` in `RealOrchestratorEnvironment`.
4. If the verdict is `NEEDS_CHANGES` or `UNCERTAIN`, do NOT auto-merge; send a Telegram notification with the PR URL and Jules's verdict.

## Solution Options

### Option A — Parse verdict from PR comments, merge via gh CLI
Read the latest Jules review comment, parse the VERDICT token, merge with `gh pr merge --squash`.
**Pros:** Minimal surface area. Reuses existing `getPrComments`. Squash keeps history clean.
**Cons:** Requires `gh pr merge` permission; must handle race between human merge and auto-merge check.
**Risk:** MEDIUM — auto-merging is irreversible. Guard with double-check of CI status immediately before merge call.
**Effort:** medium
**Files changed:** `OrchestratorEnvironment.kt`, `OrchestratorStates.kt`, `GitHubCli.kt`

### Option B — Use GitHub branch protection + auto-merge flag
Enable GitHub's native auto-merge on the PR (`gh pr merge --auto`) and let GitHub merge when all conditions are met.
**Pros:** GitHub handles the race condition.
**Cons:** Requires branch protection rules configured in the repo; less control over timing.
**Risk:** LOW
**Effort:** medium

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] `./gradlew :tools:orchestrator:test` passes.
- [ ] Unit test: `AwaitingMergeStateTest.autoMergesWhenAutonomousAndApproved`: mock env returns `autonomy=autonomous`, `solution_approved=true`, `VERDICT: APPROVED` comment → `mergePr` is called.
- [ ] Unit test: `doesNotAutoMergeWhenAutonomyIsSupervised`: `autonomy=supervised` → `mergePr` is NOT called.
- [ ] Unit test: `doesNotAutoMergeWhenVerdictIsNeedsChanges` → `mergePr` is NOT called, notification sent.
- [ ] Notification always sent before merge so human is informed.

**Implementation Hints:**
- Add a 5-second sleep between the final CI check and the merge call to reduce race probability.
- If `gh pr merge` fails (e.g., branch protection blocks it), catch the exception, send a notification, and fall back to `AWAITING_MERGE` with human prompt.
