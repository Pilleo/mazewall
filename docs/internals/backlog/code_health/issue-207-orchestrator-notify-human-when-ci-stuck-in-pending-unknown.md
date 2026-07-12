---
title: "Orchestrator: Notify Human When CI Build Status is Stuck in PENDING/UNKNOWN"
severity: "MEDIUM"
status: "open"
priority: 7
dependencies: []
component: "orchestrator"
effort: "small"
autonomy: "autonomous"
solution_approved: true
chosen_solution: "A"
blast_radius: "low"
reversible: true
github_issue: 89
---

# 🟡 [Severity: MEDIUM]: Orchestrator: Notify Human When CI Build Status is Stuck in PENDING/UNKNOWN

**Context:**
`CI_RUNNING.execute()` handles four statuses: `SUCCESS`, `FAILURE`, `CONFLICT`, and an `else` branch covering `PENDING` and `UNKNOWN`. The `else` branch sleeps for `pollingIntervalSeconds` and retries with zero notification. If the GitHub Actions runner is down, the PR check is queued but never starts, or the status API call consistently returns `UNKNOWN`, the task silently loops until `taskTimeoutThresholdMinutes` (60 min default) expires. During this time the human gets no signal and cannot intervene. This is separate from the `CONFLICT` case which does have a notification.

**Needed:**
1. Add `lastStatusChangeTime: Long = 0L` and `lastKnownStatus: String? = null` to `OrchestratorContext` (with persistence in `load`/`save`).
2. In `CI_RUNNING`, when status is `PENDING` or `UNKNOWN`: if `lastKnownStatus != status`, update `lastKnownStatus` and `lastStatusChangeTime`. If `lastKnownStatus == status` (unchanged) and `System.currentTimeMillis() - lastStatusChangeTime > stuckPendingThresholdMs` (configurable, default 15 min), send a Telegram notification and ring the bell once.
3. Add `stuckPendingThresholdMs: Long = 900_000` (15 min) to `OrchestratorConfig` with env var `STUCK_PENDING_THRESHOLD_MS`.
4. Reset `lastStatusChangeTime` and `lastKnownStatus` when entering `CI_RUNNING` from another state (i.e., when `currentSha != context.lastHeadSha`).

## Solution Options

### Option A — lastStatusChangeTime tracking in context
Track timestamp of last status change in context. Notify after configurable threshold.
**Pros:** Simple, no new state, observable in logs.
**Risk:** LOW
**Effort:** small
**Files changed:** `OrchestratorDaemon.kt`, `OrchestratorEnvironment.kt`, `OrchestratorStates.kt`

---
**Chosen:** Option A

**Acceptance Criteria:**
- [ ] `./gradlew :tools:orchestrator:test` passes.
- [ ] Unit test `CiRunningStateTest.notifiesWhenStuckInPending`: mock env returns `PENDING` repeatedly; assert notification is sent after the threshold elapses.
- [ ] Notification is sent only once per stuck period (not on every poll cycle).
- [ ] `stuckPendingThresholdMs` is loaded from `.ENV` / system property.

**Implementation Hints:**
- Note: `context.lastWaitingLogTime` is already used for CONFLICT throttling — use the same pattern for PENDING/UNKNOWN.
- Do NOT reuse `lastWaitingLogTime` for this purpose; it's shared with CONFLICT and AWAITING_MERGE. Add a new field `lastPendingNotificationTime`.
