package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit

sealed interface OrchestratorState {
    val name: String
    fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState

    fun updateSlot(slot: SlotContext) {
        slot.state = this
        when (this) {
            is SelectTaskState -> {
                // Keep the current ID if possible, otherwise SELECT_TASK will select a new one.
            }
            is PendingApprovalState -> {
                slot.currentIssueId = this.issueId
                slot.currentIssueTitle = this.issueTitle
                slot.currentIssueFile = this.issueFile
                slot.githubIssueNumber = this.githubIssueNumber
            }
            is AwaitingJulesStartState -> {
                slot.currentIssueId = this.issueId
                slot.githubIssueNumber = this.githubIssueNumber
            }
            is AwaitingPrState -> {
                slot.currentIssueId = this.issueId
                slot.githubIssueNumber = this.githubIssueNumber
                slot.julesSessionId = this.julesSessionId
            }
            is CiRunningState -> {
                slot.currentIssueId = this.issueId
                slot.githubIssueNumber = this.githubIssueNumber
                slot.julesSessionId = this.julesSessionId
                slot.prNumber = this.prNumber
            }
            is AwaitingReviewState -> {
                slot.currentIssueId = this.issueId
                slot.githubIssueNumber = this.githubIssueNumber
                slot.julesSessionId = this.julesSessionId
                slot.prNumber = this.prNumber
                slot.lastHeadSha = this.lastHeadSha
            }
            is AwaitingMergeState -> {
                slot.currentIssueId = this.issueId
                slot.githubIssueNumber = this.githubIssueNumber
                slot.julesSessionId = this.julesSessionId
                slot.prNumber = this.prNumber
                slot.lastHeadSha = this.lastHeadSha
            }
            is ResolveTaskState -> {
                slot.currentIssueId = this.issueId
            }
        }
    }

    fun execute(env: OrchestratorEnvironment, context: OrchestratorContext): OrchestratorState {
        // Find existing slot or create a default/fallback slot from the legacy context properties
        val issueId = context.currentIssueId ?: "dummy-issue-id"
        var slot = context.activeSlots.firstOrNull { it.currentIssueId == issueId }
        if (slot == null) {
            slot = SlotContext(issueId)
            context.activeSlots.add(slot)
        }

        // Always sync current state properties to slot first
        this.updateSlot(slot)

        // Always sync context fields to slot fields before execution
        slot.currentIssueTitle = context.currentIssueTitle
        slot.currentIssueFile = context.currentIssueFile
        slot.githubIssueNumber = context.githubIssueNumber
        slot.julesSessionId = context.julesSessionId
        slot.prNumber = context.prNumber

        slot.lastHeadSha = context.lastHeadSha
        slot.lastReviewedSha = context.lastReviewedSha
        slot.lastRequestedReviewSha = context.lastRequestedReviewSha
        slot.lastBuildStatus = context.lastBuildStatus
        slot.lastCheckedSha = context.lastCheckedSha
        slot.lastWaitingLogTime = context.lastWaitingLogTime
        slot.lastStatusChangeTime = context.lastStatusChangeTime
        slot.lastKnownStatus = context.lastKnownStatus
        slot.lastPendingNotificationTime = context.lastPendingNotificationTime
        slot.lastFailedSha = context.lastFailedSha
        slot.startTime = context.startTime
        slot.julesRetries = context.julesRetries
        slot.julesReviewPushCount = context.julesReviewPushCount
        slot.julesReviewAttemptCount = context.julesReviewAttemptCount
        slot.retryAfterTime = context.retryAfterTime
        slot.julesSessionFailureWaitAttempts = context.julesSessionFailureWaitAttempts
        slot.julesTriggerAttempts = context.julesTriggerAttempts
        slot.prMergeStatusAttempts = context.prMergeStatusAttempts

        val nextState = execute(env, context, slot)

        // Propagate state properties back to the slot
        nextState.updateSlot(slot)

        // Propagate changes back to legacy fields for tests asserting on context fields directly
        context.currentIssueId = slot.currentIssueId
        context.currentIssueTitle = slot.currentIssueTitle
        context.currentIssueFile = slot.currentIssueFile
        context.githubIssueNumber = slot.githubIssueNumber
        context.julesSessionId = slot.julesSessionId
        context.prNumber = slot.prNumber

        context.lastHeadSha = slot.lastHeadSha
        context.lastReviewedSha = slot.lastReviewedSha
        context.lastRequestedReviewSha = slot.lastRequestedReviewSha
        context.lastBuildStatus = slot.lastBuildStatus
        context.lastCheckedSha = slot.lastCheckedSha
        context.lastWaitingLogTime = slot.lastWaitingLogTime
        context.lastStatusChangeTime = slot.lastStatusChangeTime
        context.lastKnownStatus = slot.lastKnownStatus
        context.lastPendingNotificationTime = slot.lastPendingNotificationTime
        context.lastFailedSha = slot.lastFailedSha
        context.startTime = slot.startTime
        context.julesRetries = slot.julesRetries
        context.julesReviewPushCount = slot.julesReviewPushCount
        context.julesReviewAttemptCount = slot.julesReviewAttemptCount
        context.retryAfterTime = slot.retryAfterTime
        context.julesSessionFailureWaitAttempts = slot.julesSessionFailureWaitAttempts
        context.julesTriggerAttempts = slot.julesTriggerAttempts
        context.prMergeStatusAttempts = slot.prMergeStatusAttempts

        if (nextState is SelectTaskState && !context.activeSlots.contains(slot)) {
            context.clearActiveTask()
        }
        return nextState
    }

    companion object {
        fun fromName(name: String?): OrchestratorState {
            return when (name) {
                "SELECT_TASK" -> SelectTaskState
                "PENDING_APPROVAL", "AWAIT_START_APPROVAL" -> PendingApprovalState("", "", "")
                "AWAITING_JULES_START", "AWAIT_JULES_START" -> AwaitingJulesStartState("", "")
                "AWAITING_PR", "AWAIT_PR_CREATION" -> AwaitingPrState("", "", "")
                "CI_RUNNING", "MONITOR_PR" -> CiRunningState("", "", "", "")
                "AWAITING_REVIEW" -> AwaitingReviewState("", "", "", "", "")
                "AWAITING_MERGE" -> AwaitingMergeState("", "", "", "", "")
                "RESOLVE_TASK" -> ResolveTaskState("")
                else -> SelectTaskState
            }
        }

        fun fromSlot(slot: SlotContext, stateName: String? = null): OrchestratorState {
            val name = stateName ?: slot.state.name
            return when (name) {
                "SELECT_TASK" -> SelectTaskState
                "PENDING_APPROVAL", "AWAIT_START_APPROVAL" -> {
                    val issueId = slot.currentIssueId
                    val issueTitle = slot.currentIssueTitle ?: "Unknown Title"
                    val issueFile = slot.currentIssueFile ?: ""
                    val githubIssueNumber = slot.githubIssueNumber
                    PendingApprovalState(issueId, issueTitle, issueFile, githubIssueNumber)
                }
                "AWAITING_JULES_START", "AWAIT_JULES_START" -> {
                    val issueId = slot.currentIssueId
                    val githubIssueNumber = slot.githubIssueNumber ?: throw IllegalStateException("githubIssueNumber is null in AWAITING_JULES_START")
                    AwaitingJulesStartState(issueId, githubIssueNumber)
                }
                "AWAITING_PR", "AWAIT_PR_CREATION" -> {
                    val issueId = slot.currentIssueId
                    val githubIssueNumber = slot.githubIssueNumber ?: throw IllegalStateException("githubIssueNumber is null in AWAITING_PR")
                    val julesSessionId = slot.julesSessionId ?: throw IllegalStateException("julesSessionId is null in AWAITING_PR")
                    AwaitingPrState(issueId, githubIssueNumber, julesSessionId)
                }
                "CI_RUNNING", "MONITOR_PR" -> {
                    val issueId = slot.currentIssueId
                    val githubIssueNumber = slot.githubIssueNumber ?: throw IllegalStateException("githubIssueNumber is null in CI_RUNNING")
                    val julesSessionId = slot.julesSessionId ?: throw IllegalStateException("julesSessionId is null in CI_RUNNING")
                    val prNumber = slot.prNumber ?: throw IllegalStateException("prNumber is null in CI_RUNNING")
                    CiRunningState(issueId, githubIssueNumber, julesSessionId, prNumber)
                }
                "AWAITING_REVIEW" -> {
                    val issueId = slot.currentIssueId
                    val githubIssueNumber = slot.githubIssueNumber ?: throw IllegalStateException("githubIssueNumber is null in AWAITING_REVIEW")
                    val julesSessionId = slot.julesSessionId ?: throw IllegalStateException("julesSessionId is null in AWAITING_REVIEW")
                    val prNumber = slot.prNumber ?: throw IllegalStateException("prNumber is null in AWAITING_REVIEW")
                    val lastHeadSha = slot.lastHeadSha ?: throw IllegalStateException("lastHeadSha is null in AWAITING_REVIEW")
                    AwaitingReviewState(issueId, githubIssueNumber, julesSessionId, prNumber, lastHeadSha)
                }
                "AWAITING_MERGE" -> {
                    val issueId = slot.currentIssueId
                    val githubIssueNumber = slot.githubIssueNumber ?: throw IllegalStateException("githubIssueNumber is null in AWAITING_MERGE")
                    val julesSessionId = slot.julesSessionId ?: throw IllegalStateException("julesSessionId is null in AWAITING_MERGE")
                    val prNumber = slot.prNumber ?: throw IllegalStateException("prNumber is null in AWAITING_MERGE")
                    val lastHeadSha = slot.lastHeadSha ?: throw IllegalStateException("lastHeadSha is null in AWAITING_MERGE")
                    AwaitingMergeState(issueId, githubIssueNumber, julesSessionId, prNumber, lastHeadSha)
                }
                "RESOLVE_TASK" -> {
                    val issueId = slot.currentIssueId
                    ResolveTaskState(issueId)
                }
                else -> SelectTaskState
            }
        }

        fun fromContext(context: OrchestratorContext, stateName: String? = null): OrchestratorState {
            val name = stateName ?: context.state.name
            return when (name) {
                "SELECT_TASK" -> SelectTaskState
                "PENDING_APPROVAL", "AWAIT_START_APPROVAL" -> {
                    val issueId = context.currentIssueId ?: "dummy-issue-id"
                    val issueTitle = context.currentIssueTitle ?: "Unknown Title"
                    val issueFile = context.currentIssueFile ?: ""
                    val githubIssueNumber = context.githubIssueNumber
                    PendingApprovalState(issueId, issueTitle, issueFile, githubIssueNumber)
                }
                "AWAITING_JULES_START", "AWAIT_JULES_START" -> {
                    val issueId = context.currentIssueId ?: "dummy-issue-id"
                    val githubIssueNumber = context.githubIssueNumber ?: "dummy-github-issue"
                    AwaitingJulesStartState(issueId, githubIssueNumber)
                }
                "AWAITING_PR", "AWAIT_PR_CREATION" -> {
                    val issueId = context.currentIssueId ?: "dummy-issue-id"
                    val githubIssueNumber = context.githubIssueNumber ?: "dummy-github-issue"
                    val julesSessionId = context.julesSessionId ?: "dummy-session-id"
                    AwaitingPrState(issueId, githubIssueNumber, julesSessionId)
                }
                "CI_RUNNING", "MONITOR_PR" -> {
                    val issueId = context.currentIssueId ?: "dummy-issue-id"
                    val githubIssueNumber = context.githubIssueNumber ?: "dummy-github-issue"
                    val julesSessionId = context.julesSessionId ?: "dummy-session-id"
                    val prNumber = context.prNumber ?: "dummy-pr-number"
                    CiRunningState(issueId, githubIssueNumber, julesSessionId, prNumber)
                }
                "AWAITING_REVIEW" -> {
                    val issueId = context.currentIssueId ?: "dummy-issue-id"
                    val githubIssueNumber = context.githubIssueNumber ?: "dummy-github-issue"
                    val julesSessionId = context.julesSessionId ?: "dummy-session-id"
                    val prNumber = context.prNumber ?: "dummy-pr-number"
                    val lastHeadSha = context.lastHeadSha ?: "dummy-sha"
                    AwaitingReviewState(issueId, githubIssueNumber, julesSessionId, prNumber, lastHeadSha)
                }
                "AWAITING_MERGE" -> {
                    val issueId = context.currentIssueId ?: "dummy-issue-id"
                    val githubIssueNumber = context.githubIssueNumber ?: "dummy-github-issue"
                    val julesSessionId = context.julesSessionId ?: "dummy-session-id"
                    val prNumber = context.prNumber ?: "dummy-pr-number"
                    val lastHeadSha = context.lastHeadSha ?: "dummy-sha"
                    AwaitingMergeState(issueId, githubIssueNumber, julesSessionId, prNumber, lastHeadSha)
                }
                "RESOLVE_TASK" -> {
                    val issueId = context.currentIssueId ?: "dummy-issue-id"
                    ResolveTaskState(issueId)
                }
                else -> SelectTaskState
            }
        }
    }
}

data object SelectTaskState : OrchestratorState {
    override val name = "SELECT_TASK"
    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        // SELECT_TASK is handled globally by the daemon runner to support multi-issue parallel execution,
        // but we implement a compliant single-slot fallback for backwards compatibility / unit tests.
        val allIssues = env.parseAllIssues()
        val activeIssues = allIssues.filter { it.id !in context.skippedIds && context.activeSlots.none { s -> s.currentIssueId == it.id } }
        var selected = DependencyGraph.selectNextIssue(activeIssues)
        if (selected == null && context.skippedIds.isNotEmpty()) {
            context.skippedIds.clear()
            val resetActiveIssues = allIssues.filter { it.id !in context.skippedIds && context.activeSlots.none { s -> s.currentIssueId == it.id } }
            selected = DependencyGraph.selectNextIssue(resetActiveIssues)
        }

        if (selected == null) {
            return this
        }

        return PendingApprovalState(
            issueId = selected.id,
            issueTitle = selected.title,
            issueFile = selected.file.path,
            githubIssueNumber = selected.githubIssue?.toString()
        )
    }
}

data class PendingApprovalState(
    val issueId: String,
    val issueTitle: String,
    val issueFile: String,
    val githubIssueNumber: String? = null
) : OrchestratorState {
    override val name = "PENDING_APPROVAL"
    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return this
        }

        val approved = if (githubIssueNumber != null) {
            if (env.gitHubClient.isIssueClosed(githubIssueNumber)) {
                env.println("\n\u001B[1;33m⚠️ GitHub issue #$githubIssueNumber was closed. Resolving and canceling task $issueId.\u001B[0m")
                val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                if (nextIssue != null) {
                    env.markIssueAsResolved(nextIssue)
                }
                context.activeSlots.remove(slot)
                return SelectTaskState
            }
            if (!slot.approvalRequestSent) {
                env.println("🔄 Resuming already-in-progress task $issueId (linked to GitHub issue #$githubIssueNumber)...")
                slot.approvalRequestSent = true
            }
            true
        } else {
            if (!slot.approvalRequestSent) {
                env.ringBell(3)
                val issueFileObj = File(issueFile)
                val issue = if (issueFileObj.exists()) BacklogParser.parseIssueFile(issueFileObj) else null

                val text = if (issueFileObj.exists()) {
                    val rawBody = issueFileObj.readText().trim()
                    """
                    🤖 *Approval Request: Start Task ${issueId}*

                    $rawBody

                    ----------------------------------
                    Please approve or skip using the inline keyboard below.
                    """.trimIndent()
                } else if (issue != null) {
                    """
                    🤖 *Approval Request: Start Task ${issue.id}*
                    *Title:* ${issue.title}
                    *Severity:* ${issue.severity ?: "N/A"} | *Effort:* ${issue.effort ?: "N/A"} | *Component:* ${issue.component ?: "N/A"}

                    *Context:*
                    ${issue.context ?: "N/A"}

                    *Needed:*
                    ${issue.needed ?: "N/A"}

                    Please approve or skip using the inline keyboard below.
                    """.trimIndent()
                } else {
                    "Start task $issueId - $issueTitle?"
                }

                val truncatedText = if (text.length > 4000) text.substring(0, 3997) + "..." else text
                env.println("│██? [APPROVAL REQUIRED] Waiting for user approval on Telegram for $issueId... (Press 'Approve' or 'Skip' in Telegram)")
                env.sendApprovalRequest(issueId, truncatedText)
                slot.approvalRequestSent = true
                slot.retryAfterTime = System.currentTimeMillis() + 5000L
                return this
            }

            val res = env.checkApprovalNonBlocking(issueId)
            if (res == null) {
                slot.retryAfterTime = System.currentTimeMillis() + 5000L
                return this
            }
            res
        }

        if (!approved) {
            env.println("⏭️ Task $issueId skipped by user. Postponing.")
            context.skippedIds.add(issueId)
            context.activeSlots.remove(slot)
            return SelectTaskState
        }

        env.println("🚀 Starting task `$issueId`...")
        slot.startTime = System.currentTimeMillis()

        // Retrieve or create GitHub issue
        var newGithubIssueNumber = githubIssueNumber
        if (newGithubIssueNumber == null) {
            val existingIssueNumber = env.gitHubClient.findExistingIssueNumber(issueId)
            if (existingIssueNumber != null) {
                env.println("♻️ Recovered existing GitHub issue #$existingIssueNumber for $issueId (was missing from backlog file).")
                newGithubIssueNumber = existingIssueNumber
            } else {
                env.println("Creating GitHub issue for $issueId...")
                val issueTitleForGit = "[$issueId] $issueTitle"
                val issueFileObj = File(issueFile)
                val issueBody = if (issueFileObj.exists()) issueFileObj.readText() else ""
                val enhancedBody = OrchestratorPrompts.taskPrompt(issueBody)
                newGithubIssueNumber = env.gitHubClient.createIssue(issueTitleForGit, enhancedBody, "jules")
                env.println("Created GitHub issue #$newGithubIssueNumber")
            }
            // Write it to issue file
            val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
            if (nextIssue != null && newGithubIssueNumber != null) {
                env.writeGithubIssue(nextIssue, newGithubIssueNumber.toInt())
            }
        }

        slot.githubIssueNumber = newGithubIssueNumber
        return AwaitingJulesStartState(issueId, newGithubIssueNumber)
    }
}

data class AwaitingJulesStartState(
    val issueId: String,
    val githubIssueNumber: String
) : OrchestratorState {
    override val name = "AWAITING_JULES_START"
    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return this
        }

        if (env.gitHubClient.isIssueClosed(githubIssueNumber)) {
            env.println("\n\u001B[1;33m⚠️ GitHub issue #$githubIssueNumber was closed. Resolving and canceling task $issueId.\u001B[0m")
            val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
            if (nextIssue != null) {
                env.markIssueAsResolved(nextIssue)
            }
            context.activeSlots.remove(slot)
            return SelectTaskState
        }

        // Check if a linked PR already exists on GitHub. If so, immediately transition to CI_RUNNING
        val existingPr = env.gitHubClient.findLinkedPR(githubIssueNumber, issueId, null)
        if (existingPr != null) {
            env.println("🎉 Found already existing/linked PR #$existingPr for issue #$githubIssueNumber ($issueId). Transitioning straight to CI_RUNNING...")
            val activeSessionId = env.julesClient.getActiveSession(issueId)?.id ?: "dummy-session-id"
            slot.prNumber = existingPr
            slot.julesSessionId = activeSessionId
            slot.retryAfterTime = 0L
            slot.julesTriggerAttempts = 0
            return CiRunningState(issueId, githubIssueNumber, activeSessionId, existingPr)
        }

        val activeSession = env.julesClient.getActiveSession(issueId)
        if (activeSession != null) {
            env.println("Linked Jules session: ID=${activeSession.id}, Status=${activeSession.status}")
            slot.retryAfterTime = 0L
            slot.julesTriggerAttempts = 0
            return AwaitingPrState(issueId, githubIssueNumber, activeSession.id)
        }

        if (slot.julesTriggerAttempts < env.config.julesTriggerAttempts) {
            slot.julesTriggerAttempts++
            env.println("Waiting for Jules session to be automatically triggered via GitHub issue label (attempt ${slot.julesTriggerAttempts}/${env.config.julesTriggerAttempts})...")
            slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.julesTriggerIntervalSeconds)
            return this
        }

        if (isTaskTimedOut(slot, env.config)) {
            env.errPrintln("❌ Task $issueId timed out waiting for Jules session. Returning to SELECT_TASK.")
            context.activeSlots.remove(slot)
            return SelectTaskState
        }

        env.println("⚠️ Jules session did not trigger. Retrying in 1 minute...")
        slot.julesTriggerAttempts = 0
        slot.retryAfterTime = currentTime + TimeUnit.MINUTES.toMillis(1)
        return this
    }
}

data class AwaitingPrState(
    val issueId: String,
    val githubIssueNumber: String,
    val julesSessionId: String
) : OrchestratorState {
    override val name = "AWAITING_PR"
    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return this
        }

        if (env.gitHubClient.isIssueClosed(githubIssueNumber)) {
            env.println("\n\u001B[1;33m⚠️ GitHub issue #$githubIssueNumber was closed. Resolving and canceling task $issueId.\u001B[0m")
            val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
            if (nextIssue != null) {
                env.markIssueAsResolved(nextIssue)
            }
            context.skippedIds.add(issueId)
            context.activeSlots.remove(slot)
            return SelectTaskState
        }

        val prNumber = slot.prNumber ?: env.gitHubClient.findLinkedPR(githubIssueNumber, issueId, julesSessionId)
        if (prNumber != null) {
            if (slot.prNumber == null) {
                env.println("🎉 Jules opened PR #$prNumber")
                slot.prNumber = prNumber
                slot.lastBuildStatus = null
                slot.lastHeadSha = null
                slot.lastCheckedSha = null
                slot.julesRetries = 0
            }
            return CiRunningState(issueId, githubIssueNumber, julesSessionId, prNumber)
        }

        // 2. Check Jules session status
        val session = env.julesClient.getActiveSession(issueId)
        val currentSessionId = session?.id ?: julesSessionId
        val status = session?.status?.lowercase() ?: ""
        val isFailed = status == "failed" || status == "cancelled" ||
                env.julesClient.hasUnableToCompleteActivity(currentSessionId)

        if (isFailed) {
            if (slot.julesRetries < 2) {
                slot.julesRetries++
                env.println("\n⚠️ [RETRY] Jules task $issueId failed with status: ${session?.status ?: "FAILED"}. Retrying (Attempt ${slot.julesRetries}/2)...")
                env.sendNotification("⚠️ *Jules task failed* for $issueId (Status: ${session?.status ?: "FAILED"}). Sending 'Retry' message to Jules.")
                env.julesClient.sendSessionMessage(currentSessionId, "Retry")
                slot.lastBuildStatus = null
                return this
            } else {
                env.println("\n❌ [FAILED] Jules task $issueId failed after ${slot.julesRetries} retries.")
                env.sendNotification("❌ *Jules task failed* for $issueId after ${slot.julesRetries} retries. Returning to SELECT_TASK.")
                val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                if (nextIssue != null) {
                    env.removeGithubIssue(nextIssue)
                }
                context.skippedIds.add(issueId)
                context.activeSlots.remove(slot)
                return SelectTaskState
            }
        }

        if (session != null) {
            val sessionUrl = "https://jules.google.com/session/${session.id.substringAfterLast("/")}"
            if (session.status != slot.lastBuildStatus) {
                env.println("Jules session status changed: ${session.status}")
                slot.lastBuildStatus = session.status

                if (session.status.contains("Awaiting", ignoreCase = true) || session.status.contains("Feedback", ignoreCase = true)) {
                    val alertMsg = "⚠️ *Jules needs feedback on task $issueId!* Status: `${session.status}`. Please check and respond here: $sessionUrl"
                    env.sendNotification(alertMsg)
                    env.println("\n\u001B[1;31m🔔 [FEEDBACK REQUIRED] Jules is blocked waiting for feedback on task $issueId. Status: ${session.status}\u001B[0m")
                    env.println("👉 Respond here: $sessionUrl")
                    env.ringBell(5)
                } else if (session.status.equals("Completed", ignoreCase = true)) {
                    val isReviewTask = issueId.contains("review-task")
                    if (isReviewTask) {
                        env.println("\n\u001B[1;32m🟢 [REVIEW TASK COMPLETED] Review task $issueId is Completed!\u001B[0m")
                        env.sendNotification("🟢 *Review Task Completed!* `$issueId` (GitHub Issue #$githubIssueNumber)\n👉 Check results on GitHub issue #$githubIssueNumber or Jules UI: $sessionUrl")
                        val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                        if (nextIssue != null) {
                            env.markIssueAsResolved(nextIssue)
                        }
                        context.activeSlots.remove(slot)
                        return SelectTaskState
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - slot.lastWaitingLogTime > 600_000) {
                            env.println("\n\u001B[1;32m🟢 [COMPLETED] Jules task $issueId is Completed! Please review and publish the PR in the UI.\u001B[0m")
                            env.println("👉 Publish PR here: $sessionUrl")
                            slot.lastWaitingLogTime = now
                        }
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        if (now - slot.lastWaitingLogTime > 600_000) {
            env.println("⌛ Waiting for Jules PR to be published for task $issueId...")
            slot.lastWaitingLogTime = now
        }

        return this
    }
}

data class CiRunningState(
    val issueId: String,
    val githubIssueNumber: String,
    val julesSessionId: String,
    val prNumber: String
) : OrchestratorState {
    override val name = "CI_RUNNING"
    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return this
        }

        // Check if we are currently waiting for a failed session to transition out of failure
        if (slot.julesSessionFailureWaitAttempts > 0) {
            val retriedSession = env.julesClient.getActiveSession(issueId)
            val isStillFailed = retriedSession != null &&
                    (retriedSession.status.lowercase() == "failed" ||
                     retriedSession.status.lowercase() == "cancelled" ||
                     env.julesClient.hasUnableToCompleteActivity(retriedSession.id))
            if (isStillFailed && slot.julesSessionFailureWaitAttempts < 15) {
                env.println("Waiting for Jules session status to transition out of failure state (attempt ${slot.julesSessionFailureWaitAttempts}/15)...")
                slot.julesSessionFailureWaitAttempts++
                slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(20)
                return this
            } else {
                slot.julesSessionFailureWaitAttempts = 0
            }
        }

        val currentSha = env.gitHubClient.getPrHeadSha(prNumber)
        if (currentSha != slot.lastHeadSha) {
            env.gitHubClient.clearPrCache(prNumber)
        }

        // ⚠️ CRITICAL: Check if a Jules session is actively in progress BEFORE attempting to merge or rebase.
        // Modifying the PR branch while Jules is actively coding causes race conditions and code loss.
        val session = env.julesClient.getActiveSession(issueId)
        val isFailed = if (session != null) {
            val stat = session.status.lowercase()
            stat == "failed" || stat == "cancelled" || env.julesClient.hasUnableToCompleteActivity(session.id)
        } else {
            env.julesClient.hasUnableToCompleteActivity(julesSessionId)
        }

        if (isFailed) {
            val statText = session?.status ?: "FAILED"
            if (slot.julesRetries < 2) {
                slot.julesRetries++
                env.println("\n⚠️ [RETRY] Jules session failed during CI: $statText (or has unable to complete activity). Retrying (Attempt ${slot.julesRetries}/2)...")
                env.sendNotification("⚠️ *Jules session failed* during CI for $issueId (Status: $statText). Sending 'Retry' message to Jules (Attempt ${slot.julesRetries}/2).")
                val targetSessionId = session?.id ?: julesSessionId
                env.julesClient.sendSessionMessage(targetSessionId, "Retry")
                slot.lastBuildStatus = null
                slot.lastHeadSha = null

                slot.julesSessionFailureWaitAttempts = 1
                slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(20)
                return this
            } else {
                env.println("\n❌ [FAILED] Jules session failed during CI: $statText after ${slot.julesRetries} retries.")
                env.sendNotification("❌ *Jules session failed* during CI for $issueId after ${slot.julesRetries} retries. Returning to SELECT_TASK.")
                val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                if (nextIssue != null) {
                    env.removeGithubIssue(nextIssue)
                }
                context.skippedIds.add(issueId)
                context.activeSlots.remove(slot)
                return SelectTaskState
            }
        }

        if (session != null && session.status.lowercase() == "in_progress") {
            env.println("Jules session ${session.id} is actively running (IN_PROGRESS). Waiting...")
            slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
            return this
        }

        if (handleRebaseAndConflicts(env, slot, prNumber)) {
            if (slot.retryAfterTime <= currentTime) {
                slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
            }
            return this
        }

        if (env.gitHubClient.isPrMerged(prNumber)) {
            env.println("🎉 PR #$prNumber merged! resolving issue locally...")
            return ResolveTaskState(issueId)
        }

        if (currentSha != slot.lastHeadSha) {
            env.println("🔄 New commits detected on PR #$prNumber (Head SHA: $currentSha). Checking build status...")
            slot.lastHeadSha = currentSha
            slot.lastKnownStatus = null
            slot.lastStatusChangeTime = 0L
            slot.lastPendingNotificationTime = 0L
            slot.lastRequestedReviewSha = null // Reset requested SHA for new commits!
        }

        val status = env.gitHubClient.checkBuildStatus(prNumber)
        if (status != slot.lastBuildStatus || currentSha != slot.lastCheckedSha) {
            env.println("PR #$prNumber build check: $status")
            slot.lastBuildStatus = status
            slot.lastCheckedSha = currentSha
        }

        return when (status) {
            "SUCCESS" -> AwaitingReviewState(issueId, githubIssueNumber, julesSessionId, prNumber, currentSha)
            "FAILURE" -> {
                val headSha = env.gitHubClient.getPrHeadSha(prNumber)
                if (headSha != slot.lastFailedSha) {
                    env.println("❌ Build failed on PR #$prNumber. Fetching logs...")
                    val failedLogs = env.gitHubClient.getFailedBuildLogs(prNumber)
                    val feedback = """
                        ❌ **CI Build Failed.**
                        @jules Please review the failing logs and fix the implementation:

                        ```
                        $failedLogs
                        ```
                    """.trimIndent()

                    env.gitHubClient.commentOnPr(prNumber, feedback)
                    env.sendNotification("❌ Build failed on PR #$prNumber. Feedback sent to Jules.")
                    slot.lastFailedSha = headSha
                } else {
                    env.println("❌ Build is still failing on SHA $headSha. Waiting for a new commit...")
                }
                slot.retryAfterTime = currentTime + TimeUnit.MINUTES.toMillis(env.config.ciFailureRetryMinutes)
                this
            }
            "CONFLICT" -> {
                slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
                this
            }
            else -> {
                val now = System.currentTimeMillis()
                if (status != slot.lastKnownStatus) {
                    slot.lastKnownStatus = status
                    slot.lastStatusChangeTime = now
                    slot.lastPendingNotificationTime = 0L
                } else if (now - slot.lastStatusChangeTime > env.config.stuckPendingThresholdMs && slot.lastPendingNotificationTime == 0L) {
                    val prUrl = env.gitHubClient.getPrUrl(prNumber)
                    val msg = "⚠️ *PR #$prNumber build status is stuck in $status!* Please check the runner: $prUrl"
                    env.println("\u001B[1;31m🔔 [STUCK] PR #$prNumber build status is stuck in $status! Please check the runner: $prUrl\u001B[0m")
                    env.sendNotification(msg)
                    env.ringBell(1)
                    slot.lastPendingNotificationTime = now
                }
                slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
                this
            }
        }
    }
}

data class AwaitingReviewState(
    val issueId: String,
    val githubIssueNumber: String,
    val julesSessionId: String,
    val prNumber: String,
    val lastHeadSha: String
) : OrchestratorState {
    override val name = "AWAITING_REVIEW"
    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return this
        }

        // Check if we are currently waiting for a failed session to transition out of failure
        if (slot.julesSessionFailureWaitAttempts > 0) {
            val retriedSession = env.julesClient.getActiveSession(issueId)
            val isStillFailed = retriedSession != null &&
                    (retriedSession.status.lowercase() == "failed" ||
                     retriedSession.status.lowercase() == "cancelled" ||
                     env.julesClient.hasUnableToCompleteActivity(retriedSession.id))
            if (isStillFailed && slot.julesSessionFailureWaitAttempts < 15) {
                env.println("Waiting for Jules session status to transition out of failure state (attempt ${slot.julesSessionFailureWaitAttempts}/15)...")
                slot.julesSessionFailureWaitAttempts++
                slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(20)
                return this
            } else {
                slot.julesSessionFailureWaitAttempts = 0
            }
        }

        if (env.gitHubClient.isPrMerged(prNumber)) {
            env.println("🎉 PR #$prNumber merged! resolving issue locally...")
            return ResolveTaskState(issueId)
        }

        val currentSha = env.gitHubClient.getPrHeadSha(prNumber)
        if (currentSha != slot.lastHeadSha) {
            env.gitHubClient.clearPrCache(prNumber)
        }

        // ⚠️ CRITICAL: Check if a Jules session is actively in progress BEFORE attempting to merge or rebase.
        // Modifying the PR branch while Jules is actively coding/reviewing causes race conditions and code loss.
        val session = env.julesClient.getActiveSession(issueId)
        val isFailed = if (session != null) {
            val stat = session.status.lowercase()
            stat == "failed" || stat == "cancelled" || env.julesClient.hasUnableToCompleteActivity(session.id)
        } else {
            env.julesClient.hasUnableToCompleteActivity(julesSessionId)
        }

        if (isFailed) {
            val statText = session?.status ?: "FAILED"
            env.println("\n⚠️ [RETRY] Jules session failed during review: $statText (or has unable to complete activity). Retrying...")
            env.sendNotification("⚠️ *Jules session failed* during review on PR #$prNumber. Sending 'Retry' message to Jules.")
            val targetSessionId = session?.id ?: julesSessionId
            env.julesClient.sendSessionMessage(targetSessionId, "Retry")
            slot.lastReviewedSha = null

            slot.julesSessionFailureWaitAttempts = 1
            slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(20)
            return this
        }

        if (session != null && session.status.lowercase() == "in_progress") {
            env.println("Jules session ${session.id} is actively running (IN_PROGRESS) in AWAITING_REVIEW. Waiting...")
            slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
            return this
        }

        if (handleRebaseAndConflicts(env, slot, prNumber)) {
            if (slot.retryAfterTime <= currentTime) {
                slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
            }
            return CiRunningState(issueId, githubIssueNumber, julesSessionId, prNumber)
        }

        val buildStatus = env.gitHubClient.checkBuildStatus(prNumber)

        // If the PR head SHA changed it means Jules pushed a new commit instead of just reviewing.
        if (currentSha != slot.lastHeadSha) {
            val shaOld = slot.lastHeadSha ?: ""
            val isEmpty = if (shaOld.isNotEmpty()) {
                env.gitHubClient.isCommitEmpty(prNumber, shaOld, currentSha)
            } else {
                false
            }

            slot.lastHeadSha = currentSha // Update the head SHA to the new one
            slot.lastRequestedReviewSha = null // Reset requested SHA for new commits!

            if (isEmpty) {
                env.println("⚠️ Jules pushed an empty commit during review phase on PR #$prNumber")
                env.sendNotification("⚠️ Jules pushed an empty commit during review phase on PR #$prNumber")
                env.ringBell(5)
                return AwaitingMergeState(issueId, githubIssueNumber, julesSessionId, prNumber, currentSha)
            } else {
                slot.julesReviewPushCount = 0
                env.println("🟢 Jules pushed a non-empty commit on PR #$prNumber. Treating as real problem resolution. Resetting push count and returning to CI_RUNNING.")
                return CiRunningState(issueId, githubIssueNumber, julesSessionId, prNumber)
            }
        }

        if (buildStatus != "SUCCESS") {
            return CiRunningState(issueId, githubIssueNumber, julesSessionId, prNumber)
        }

        if (currentSha != slot.lastReviewedSha) {
            if (slot.julesReviewAttemptCount >= 3) {
                env.println("⚠️ PR #$prNumber Build Passed, but Jules review attempt count exceeded limit (3). Bypassing review.")
                env.sendNotification("⚠️ PR #$prNumber: Bypassing Jules review (attempt count exceeded limit).")
                slot.lastReviewedSha = currentSha
                return AwaitingMergeState(issueId, githubIssueNumber, julesSessionId, prNumber, currentSha)
            }

            val comments = env.gitHubClient.getPrComments(prNumber)
            val searchSha = slot.lastRequestedReviewSha ?: currentSha
            val shaPrefix = searchSha.take(7)

            val requestComment = comments.firstOrNull {
                (it.body.contains("@jules")) &&
                it.body.contains(shaPrefix)
            }

            if (requestComment == null) {
                val currentShaPrefix = currentSha.take(7)

                env.println("🤖 PR #$prNumber Build Passed. Requesting Jules review for SHA: $currentSha (Attempt ${slot.julesReviewAttemptCount + 1}/3)")
                slot.julesReviewAttemptCount++

                // If Jules already pushed once instead of reviewing, use a stronger framing.
                val pushWarning = if (slot.julesReviewPushCount > 0) {
                    "\n\n🚨 **IMPORTANT — PREVIOUS ATTEMPT PUSHED CODE**: Your previous review attempt " +
                    "resulted in a commit push instead of a comment. This is incorrect. " +
                    "You must NOT push anything. Read the instructions below carefully before acting."
                } else ""

                val prompt = OrchestratorPrompts.reviewPrompt(prNumber, currentShaPrefix, pushWarning)

                env.gitHubClient.commentOnPr(prNumber, prompt)
                slot.lastRequestedReviewSha = currentSha // Record the requested SHA
                slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
                return this
            } else {
                val requestTime = java.time.Instant.parse(requestComment.createdAt)
                val julesReply = comments.firstOrNull { comment ->
                    val author = comment.author?.login ?: ""
                    (author.contains("jules", ignoreCase = true)) &&
                    java.time.Instant.parse(comment.createdAt).isAfter(requestTime)
                }

                if (julesReply != null) {
                    env.println("🟢 Jules review received for SHA $currentSha.")
                    val verdict = when {
                        julesReply.body.contains("VERDICT: APPROVED") -> "✅ APPROVED"
                        julesReply.body.contains("VERDICT: NEEDS_CHANGES") -> "🔶 NEEDS_CHANGES"
                        julesReply.body.contains("VERDICT: UNCERTAIN") -> "❓ UNCERTAIN"
                        else -> "⚠️ NO_VERDICT (Jules did not include a structured verdict)"
                    }
                    env.println("Jules verdict on PR #$prNumber: $verdict")
                    val prUrl = env.gitHubClient.getPrUrl(prNumber)
                    env.sendNotification("🟢 *Jules reviewed PR #$prNumber!* Verdict: $verdict\nReady for merge: $prUrl")
                    slot.lastReviewedSha = currentSha
                    env.ringBell(3)
                    return AwaitingMergeState(issueId, githubIssueNumber, julesSessionId, prNumber, currentSha)
                } else {
                    env.println("⌛ Waiting for Jules (@jules) to complete review on PR #$prNumber (SHA: $shaPrefix)...")
                    slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
                    return this
                }
            }
        }
        return AwaitingMergeState(issueId, githubIssueNumber, julesSessionId, prNumber, currentSha)
    }
}

data class AwaitingMergeState(
    val issueId: String,
    val githubIssueNumber: String,
    val julesSessionId: String,
    val prNumber: String,
    val lastHeadSha: String
) : OrchestratorState {
    override val name = "AWAITING_MERGE"
    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return this
        }

        if (env.gitHubClient.isPrMerged(prNumber)) {
            env.println("🎉 PR #$prNumber merged! resolving issue locally...")
            return ResolveTaskState(issueId)
        }

        val currentSha = env.gitHubClient.getPrHeadSha(prNumber)
        if (currentSha != slot.lastHeadSha) {
            return CiRunningState(issueId, githubIssueNumber, julesSessionId, prNumber)
        }

        val status = env.gitHubClient.checkBuildStatus(prNumber)
        if (status != "SUCCESS") {
            return CiRunningState(issueId, githubIssueNumber, julesSessionId, prNumber)
        }

        val now = System.currentTimeMillis()
        if (now - slot.lastWaitingLogTime > 600_000) {
            val prUrl = env.gitHubClient.getPrUrl(prNumber)
            env.println("⌛ Waiting for manual merge of PR #$prNumber at: $prUrl")
            env.sendNotification("⌛ Waiting for manual merge of PR #$prNumber at: $prUrl")
            slot.lastWaitingLogTime = now
        }
        slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
        return this
    }
}

data class ResolveTaskState(
    val issueId: String
) : OrchestratorState {
    override val name = "RESOLVE_TASK"
    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }

        if (nextIssue != null) {
            env.markIssueAsResolved(nextIssue)
        } else {
            env.println("⚠️ Issue `$issueId` not found in active backlog. It may have already been resolved and moved in the merged PR.")
        }

        env.println("Regenerating architectural maps...")
        env.generateKnowledgeMap()
        env.println("✅ Resolved issue `$issueId`. Picking next task...")

        context.activeSlots.remove(slot)
        env.deleteStateFile()
        return SelectTaskState
    }
}

fun isTaskTimedOut(slot: SlotContext, config: OrchestratorConfig): Boolean {
    if (slot.startTime == 0L) return false
    val now = System.currentTimeMillis()
    val elapsedMinutes = TimeUnit.MILLISECONDS.toMinutes(now - slot.startTime)
    return elapsedMinutes >= config.taskTimeoutThresholdMinutes
}

private fun handleRebaseAndConflicts(env: OrchestratorEnvironment, slot: SlotContext, prNumber: String): Boolean {
    val currentTime = System.currentTimeMillis()
    var status = env.gitHubClient.getPrMergeStatus(prNumber)
    if (status.isError) {
        env.println("⚠️ Error retrieving PR merge status: ${status.errorMessage}. Retrying status retrieval...")
        if (status.isAuthError() && slot.prMergeStatusAttempts == 0) {
            env.sendNotification("🚨 *GitHub CLI Authentication/Query Failure on PR #$prNumber!* Error: ${status.errorMessage}")
        }

        slot.prMergeStatusAttempts++
        if (slot.prMergeStatusAttempts < 3) {
            env.println("🔄 Retrying PR merge status retrieval (attempt ${slot.prMergeStatusAttempts + 1}/3)...")
            slot.retryAfterTime = currentTime + 2000L
            return true
        } else {
            slot.prMergeStatusAttempts = 0
            env.println("❌ Failed to retrieve PR merge status after retries. Aborting current check iteration and waiting.")
            slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
            return true
        }
    }

    slot.prMergeStatusAttempts = 0

    val isBehind = status.behindBy > 0
    val isConflicting = status.mergeable == "CONFLICTING"

    if (isBehind || isConflicting) {
        val currentSha = env.gitHubClient.getPrHeadSha(prNumber)
        if (slot.failedRebaseHeadSha == currentSha) {
            val now = System.currentTimeMillis()
            if (now - slot.lastWaitingLogTime > 300_000) {
                val prUrl = env.gitHubClient.getPrUrl(prNumber)
                env.println("⚠️ PR #$prNumber has conflict/rebase failure at SHA ${currentSha.take(7)}. Automated rebase previously failed. Waiting for manual resolution or new commit: $prUrl")
                slot.lastWaitingLogTime = now
            }
            return false
        }

        val reason = if (isConflicting) "conflict status" else "behind master by ${status.behindBy} commits"
        env.println("🔄 Active PR #$prNumber is $reason. Attempting automated merge of master into branch...")

        val sessionId = env.julesClient.getActiveSession(slot.currentIssueId)?.id
        val issue = env.parseAllIssues().firstOrNull { it.id == slot.currentIssueId }
        val targetFiles = issue?.targetFiles ?: emptyList()
        val rebaseResult = env.gitHubClient.mergeMasterIntoBranch(prNumber, sessionId, targetFiles)
        if (rebaseResult.needsRescueApproval && rebaseResult.rescueBranchName != null) {
            env.println("🚨 PR #$prNumber has unrelated histories. Rescue branch prepared.")
            env.sendNotification( "🚨 Unrelated histories detected on PR #$prNumber. I've prepared a rescued branch: ${rebaseResult.rescueBranchName}. Approve to forcefully overwrite the PR branch.")
            val approved = env.requestApproval(slot.currentIssueId, "🚨 Unrelated histories detected on PR #${prNumber}. I've prepared a rescued branch: ${rebaseResult.rescueBranchName}. Approve to forcefully overwrite the PR branch.")
            if (approved) {
                env.println("Rescue approved. Pushing to PR...")
                env.gitHubClient.approveRescue(prNumber, rebaseResult.rescueBranchName)
                slot.lastWaitingLogTime = 0L
                val newSha = env.gitHubClient.getPrHeadSha(prNumber)
                val oldSha = slot.lastHeadSha
                slot.lastHeadSha = newSha
                if (slot.lastReviewedSha == oldSha && oldSha != null) slot.lastReviewedSha = newSha
                if (slot.lastRequestedReviewSha == oldSha && oldSha != null) slot.lastRequestedReviewSha = newSha
                return true
            } else {
                env.println("Rescue rejected by user.")
                return false
            }
        }
        val rebaseSuccess = rebaseResult.success
        if (rebaseSuccess) {
            env.println("✅ Successfully auto-merged master into PR #$prNumber.")
            slot.failedRebaseHeadSha = null
            slot.lastWaitingLogTime = 0L

            // Fetch the new head SHA after successful merge and update slot properties
            val newSha = env.gitHubClient.getPrHeadSha(prNumber)
            val oldSha = slot.lastHeadSha
            slot.lastHeadSha = newSha

            // If Jules already reviewed the pre-merge commit, preserve the reviewed status for the merged commit
            if (slot.lastReviewedSha == oldSha && oldSha != null) {
                slot.lastReviewedSha = newSha
            }
            // If a review comment was requested on the pre-merge commit, update the last requested SHA
            // so we recognize the existing comment for the merged commit
            if (slot.lastRequestedReviewSha == oldSha && oldSha != null) {
                slot.lastRequestedReviewSha = newSha
            }
            return true
        } else {
            slot.failedRebaseHeadSha = currentSha
            val now = System.currentTimeMillis()
            if (now - slot.lastWaitingLogTime > 60_000) {
                val prUrl = env.gitHubClient.getPrUrl(prNumber)
                val conflictSuffix = if (rebaseResult.conflictCount > 0) " (Conflicts in ${rebaseResult.conflictCount} files: ${rebaseResult.conflictedFiles})" else ""
                env.sendNotification("⚠️ *PR #$prNumber has conflicts!* Automated local worktree merge failed$conflictSuffix. Human intervention required: $prUrl")
                env.println("\u001B[1;31m🔔 [CONFLICT] PR #$prNumber has conflicts! Automated local worktree merge failed$conflictSuffix. Please resolve: $prUrl\u001B[0m")
                env.ringBell(3)
                slot.lastWaitingLogTime = now
            }
        }
    }
    return false
}
