---
title: "Orchestrator: Validate Issue Context and Needed Before GitHub Issue Creation"
severity: "HIGH"
status: "open"
priority: 10
dependencies: ["issue-202"]
component: "orchestrator"
effort: "trivial"
reversible: true
autonomy: "supervised"
solution_approved: false
blast_radius: "medium"
reversible: true
---

# 🔴 [Severity: HIGH]: Orchestrator: Validate Issue Context and Needed Before GitHub Issue Creation

**Context:**
`PENDING_APPROVAL` creates the GitHub issue immediately after human approval without checking whether the parsed `BacklogIssue` has meaningful content. If `issue.context` or `issue.needed` is `null` or blank (which happens for ~30% of current backlog files that use non-canonical section headers such as `**Target Area:**` instead of `**Context:**`), Jules receives a stub issue with no instructions and will either fail, retry forever, or produce a completely irrelevant PR. This is currently silent — the orchestrator continues as if the issue is well-formed.

**Needed:**
In `PENDING_APPROVAL.execute()`, immediately after the issue is parsed (line ~69) and before `createIssue` is called, add a validation block:
```kotlin
if (issue.context.isNullOrBlank() || issue.needed.isNullOrBlank()) {
    val msg = "⚠️ Task $issueId is missing required Context or Needed sections. " +
              "Fix the backlog file and retry. Skipping for now."
    env.errPrintln(msg)
    env.sendNotification(msg)
    context.skippedIds.add(issueId)
    return SELECT_TASK
}
```
Also add a warning (not skip) if `issue.component == "unknown"` or `issue.priority == 0`.

## Solution Options

### Option A — Inline validation in PENDING_APPROVAL
Add validation directly in the `PENDING_APPROVAL` state before `createIssue`.
**Pros:** Simple, no new abstractions, minimal diff.
**Cons:** None significant.
**Risk:** LOW
**Effort:** trivial
**Files changed:** `OrchestratorStates.kt`

---
**Chosen:** Option A
**Rationale:** The validation is a one-time guard at the earliest possible point. No new abstraction needed.

**Acceptance Criteria:**
- [ ] `./gradlew :tools:orchestrator:test` passes.
- [ ] New unit test `PendingApprovalStateTest.skipsIssueWithMissingContext` verifies: given an issue with null context, `PENDING_APPROVAL` returns `SELECT_TASK` and adds `issueId` to `skippedIds`.
- [ ] New unit test `skipsIssueWithMissingNeeded` same pattern for null needed.
- [ ] Telegram notification is sent in both cases (verified via mock env).