---
title: "Orchestrator: Close GitHub Issue Automatically on RESOLVE_TASK"
severity: "LOW"
status: "open"
priority: 6
dependencies: []
component: "orchestrator"
effort: "trivial"
autonomy: "supervised"
solution_approved: false
blast_radius: "low"
reversible: true
---

# 🔵 [Severity: LOW]: Orchestrator: Close GitHub Issue Automatically on RESOLVE_TASK

**Context:**
`RESOLVE_TASK.execute()` marks the local backlog file as resolved, moves it to `docs/internals/backlog/resolved/`, and regenerates the knowledge map. However, it never closes the corresponding GitHub issue. After a PR is merged, the GitHub issue that was used to trigger Jules remains permanently open, cluttering the issue tracker and confusing anyone who looks at open issues. The `context.githubIssueNumber` and `context.prNumber` are both available at this point.

**Needed:**
In `RESOLVE_TASK.execute()`, after `env.markIssueAsResolved(nextIssue)` and before `context.clearActiveTask()`, add a call to close the GitHub issue:
```kotlin
val issueNum = context.githubIssueNumber
val prNum = context.prNumber
if (issueNum != null) {
    env.closeIssue(issueNum, prNum?.let { "Resolved via PR #$it." } ?: "Resolved.")
}
```
Add `fun closeIssue(issueNumber: String, comment: String)` to `OrchestratorEnvironment` and implement in `RealOrchestratorEnvironment` using:
```bash
gh issue close {issueNumber} --comment "{comment}"
```

## Solution Options

### Option A — New closeIssue method on OrchestratorEnvironment
Add the interface method and real implementation. Call it from RESOLVE_TASK.
**Pros:** Clean, testable via mock env, consistent with existing patterns.
**Risk:** LOW
**Effort:** trivial
**Files changed:** `OrchestratorEnvironment.kt`, `OrchestratorStates.kt`

---
**Chosen:** *(not yet approved — requires human decision)*

**Acceptance Criteria:**
- [ ] `./gradlew :tools:orchestrator:test` passes.
- [ ] Unit test `ResolveTaskStateTest.closesGithubIssueOnResolve`: mock env verifies `closeIssue` is called with the correct issue number and a comment containing the PR number.
- [ ] `MockOrchestratorEnvironment` implements `closeIssue` as a no-op that records the call.
