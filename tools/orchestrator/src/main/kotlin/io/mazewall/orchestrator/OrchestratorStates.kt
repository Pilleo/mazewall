package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit

sealed interface OrchestratorState {
    val name: String
    fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState

    fun execute(env: OrchestratorEnvironment, context: OrchestratorContext): OrchestratorState {
        // Find existing slot or create a default/fallback slot from the legacy context properties
        val issueId = context.currentIssueId ?: "dummy-issue-id"
        var slot = context.activeSlots.firstOrNull { it.currentIssueId == issueId }
        if (slot == null) {
            slot = SlotContext(issueId)
            context.activeSlots.add(slot)
        }

        // Always sync context fields to slot fields before execution
        slot.state = this
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

        val nextState = execute(env, context, slot)

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

        if (nextState == SELECT_TASK && !context.activeSlots.contains(slot)) {
            context.clearActiveTask()
        }
        return nextState
    }

    data object SELECT_TASK : OrchestratorState {
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

            slot.currentIssueId = selected.id
            slot.currentIssueTitle = selected.title
            slot.currentIssueFile = selected.file.path
            slot.githubIssueNumber = selected.githubIssue?.toString()
            slot.julesSessionId = null
            slot.prNumber = null
            return PENDING_APPROVAL
        }
    }

    data object PENDING_APPROVAL : OrchestratorState {
        override val name = "PENDING_APPROVAL"
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
            val issueId = slot.currentIssueId
            val issueTitle = slot.currentIssueTitle ?: throw IllegalStateException("currentIssueTitle is null")
            val githubIssueNumber = slot.githubIssueNumber

            val approved = if (githubIssueNumber != null) {
                if (env.gitHubClient.isIssueClosed(githubIssueNumber)) {
                    env.println("\n\u001B[1;33m⚠️ GitHub issue #$githubIssueNumber was closed. Resolving and canceling task $issueId.\u001B[0m")
                    val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                    if (nextIssue != null) {
                        env.markIssueAsResolved(nextIssue)
                    }
                    context.activeSlots.remove(slot)
                    return SELECT_TASK
                }
                env.println("🔄 Resuming already-in-progress task $issueId (linked to GitHub issue #$githubIssueNumber)...")
                true
            } else {
                env.ringBell(3)
                val issueFile = slot.currentIssueFile?.let { File(it) }
                val issue = issueFile?.let { BacklogParser.parseIssueFile(it) }

                val text = if (issueFile != null && issueFile.exists()) {
                    val rawBody = issueFile.readText().trim()
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
                val approved = env.requestApproval(issueId, truncatedText)
                approved
            }

            if (!approved) {
                env.println("⏭️ Task $issueId skipped by user. Postponing.")
                context.skippedIds.add(issueId)
                context.activeSlots.remove(slot)
                return SELECT_TASK
            }

            env.println("🚀 Starting task `$issueId`...")
            slot.startTime = System.currentTimeMillis()

            // Retrieve or create GitHub issue
            var newGithubIssueNumber = slot.githubIssueNumber
            if (newGithubIssueNumber == null) {
                val existingIssueNumber = env.gitHubClient.findExistingIssueNumber(issueId)
                if (existingIssueNumber != null) {
                    env.println("♻️ Recovered existing GitHub issue #$existingIssueNumber for $issueId (was missing from backlog file).")
                    newGithubIssueNumber = existingIssueNumber
                } else {
                    env.println("Creating GitHub issue for $issueId...")
                    val issueTitleForGit = "[$issueId] $issueTitle"
                    val issueFile = File(slot.currentIssueFile!!)
                    val issueBody = issueFile.readText()
                    val enhancedBody = OrchestratorPrompts.taskPrompt(issueBody)
                    newGithubIssueNumber = env.gitHubClient.createIssue(issueTitleForGit, enhancedBody, "jules")
                    env.println("Created GitHub issue #$newGithubIssueNumber")
                }
                // Write it to issue file
                val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                if (nextIssue != null) {
                    env.writeGithubIssue(nextIssue, newGithubIssueNumber.toInt())
                }
                slot.githubIssueNumber = newGithubIssueNumber
            }

            return AWAITING_JULES_START
        }
    }

    data object AWAITING_JULES_START : OrchestratorState {
        override val name = "AWAITING_JULES_START"
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
            val issueId = slot.currentIssueId
            val githubIssueNumber = slot.githubIssueNumber

            if (githubIssueNumber != null && env.gitHubClient.isIssueClosed(githubIssueNumber)) {
                env.println("\n\u001B[1;33m⚠️ GitHub issue #$githubIssueNumber was closed. Resolving and canceling task $issueId.\u001B[0m")
                val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                if (nextIssue != null) {
                    env.markIssueAsResolved(nextIssue)
                }
                context.activeSlots.remove(slot)
                return SELECT_TASK
            }

            var activeSession = env.julesClient.getActiveSession(issueId)
            var attempts = 0
            while (activeSession == null && attempts < env.config.julesTriggerAttempts) {
                env.println("Waiting for Jules session to be automatically triggered via GitHub issue label (attempt ${attempts + 1}/${env.config.julesTriggerAttempts})...")
                env.sleep(env.config.julesTriggerIntervalSeconds, TimeUnit.SECONDS)
                activeSession = env.julesClient.getActiveSession(issueId)
                attempts++
            }

            return if (activeSession != null) {
                env.println("Linked Jules session: ID=${activeSession.id}, Status=${activeSession.status}")
                slot.julesSessionId = activeSession.id
                AWAITING_PR
            } else {
                if (isTaskTimedOut(slot, env.config)) {
                    env.errPrintln("❌ Task $issueId timed out waiting for Jules session. Returning to SELECT_TASK.")
                    context.activeSlots.remove(slot)
                    return SELECT_TASK
                }
                env.println("⚠️ Jules session did not trigger. Retrying in 1 minute...")
                env.sleep(1, TimeUnit.MINUTES)
                this
            }
        }
    }

    data object AWAITING_PR : OrchestratorState {
        override val name = "AWAITING_PR"
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
            val issueId = slot.currentIssueId
            val githubIssueNumber = slot.githubIssueNumber ?: throw IllegalStateException("githubIssueNumber is null")
            val sessionId = slot.julesSessionId

            if (env.gitHubClient.isIssueClosed(githubIssueNumber)) {
                env.println("\n\u001B[1;33m⚠️ GitHub issue #$githubIssueNumber was closed. Resolving and canceling task $issueId.\u001B[0m")
                val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                if (nextIssue != null) {
                    env.markIssueAsResolved(nextIssue)
                }
                context.skippedIds.add(issueId)
                context.activeSlots.remove(slot)
                return SELECT_TASK
            }

            val prNumber = slot.prNumber ?: env.gitHubClient.findLinkedPR(githubIssueNumber, issueId, sessionId)
            if (prNumber != null) {
                if (slot.prNumber == null) {
                    env.println("🎉 Jules opened PR #$prNumber")
                    slot.prNumber = prNumber
                    slot.lastBuildStatus = null
                    slot.lastHeadSha = null
                    slot.lastCheckedSha = null
                    slot.julesRetries = 0
                }

                val currentSha = env.gitHubClient.getPrHeadSha(prNumber)
                if (currentSha != slot.lastHeadSha) {
                    env.gitHubClient.clearPrCache(prNumber)
                }

                if (handleRebaseAndConflicts(env, slot, prNumber)) {
                    env.sleep(env.config.pollingIntervalSeconds, TimeUnit.SECONDS)
                }
                return CI_RUNNING
            }

            // 2. Check Jules session status
            val session = env.julesClient.getActiveSession(issueId)
            val currentSessionId = session?.id ?: sessionId
            val status = session?.status?.lowercase() ?: ""
            val isFailed = status == "failed" || status == "cancelled" ||
                    (currentSessionId != null && env.julesClient.hasUnableToCompleteActivity(currentSessionId))

            if (isFailed && currentSessionId != null) {
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
                    return SELECT_TASK
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
                            return SELECT_TASK
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

    data object CI_RUNNING : OrchestratorState {
        override val name = "CI_RUNNING"
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
            val prNumber = slot.prNumber ?: throw IllegalStateException("prNumber is null")
            val issueId = slot.currentIssueId
            val githubIssueNumber = slot.githubIssueNumber
            val sessionId = slot.julesSessionId

            val currentSha = env.gitHubClient.getPrHeadSha(prNumber)
            if (currentSha != slot.lastHeadSha) {
                env.gitHubClient.clearPrCache(prNumber)
            }

            if (handleRebaseAndConflicts(env, slot, prNumber)) {
                env.sleep(env.config.pollingIntervalSeconds, TimeUnit.SECONDS)
                return this
            }

            if (githubIssueNumber != null) {
                val session = env.julesClient.getActiveSession(issueId)
                val isFailed = if (session != null) {
                    val stat = session.status.lowercase()
                    stat == "failed" || stat == "cancelled" || env.julesClient.hasUnableToCompleteActivity(session.id)
                } else {
                    sessionId != null && env.julesClient.hasUnableToCompleteActivity(sessionId)
                }

                if (isFailed) {
                    val statText = session?.status ?: "FAILED"
                    if (slot.julesRetries < 2) {
                        slot.julesRetries++
                        env.println("\n⚠️ [RETRY] Jules session failed during CI: $statText (or has unable to complete activity). Retrying (Attempt ${slot.julesRetries}/2)...")
                        env.sendNotification("⚠️ *Jules session failed* during CI for $issueId (Status: $statText). Sending 'Retry' message to Jules (Attempt ${slot.julesRetries}/2).")
                        val targetSessionId = session?.id ?: sessionId ?: throw IllegalStateException("session ID is null")
                        env.julesClient.sendSessionMessage(targetSessionId, "Retry")
                        slot.lastBuildStatus = null
                        slot.lastHeadSha = null
                        
                        var retriedSession = env.julesClient.getActiveSession(issueId)
                        var retryWaitAttempts = 0
                        while (retriedSession != null && 
                               (retriedSession.status.lowercase() == "failed" || retriedSession.status.lowercase() == "cancelled" || env.julesClient.hasUnableToCompleteActivity(retriedSession.id)) &&
                               retryWaitAttempts < 15) {
                            env.println("Waiting for Jules session status to transition out of failure state (attempt ${retryWaitAttempts + 1}/15)...")
                            env.sleep(20, TimeUnit.SECONDS)
                            retriedSession = env.julesClient.getActiveSession(issueId)
                            retryWaitAttempts++
                        }
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
                        return SELECT_TASK
                    }
                }

                if (session != null && session.status.lowercase() == "in_progress") {
                    env.println("Jules session ${session.id} is actively running (IN_PROGRESS). Waiting...")
                    env.sleep(env.config.pollingIntervalSeconds, TimeUnit.SECONDS)
                    return this
                }
            }

            if (env.gitHubClient.isPrMerged(prNumber)) {
                env.println("🎉 PR #$prNumber merged! resolving issue locally...")
                return RESOLVE_TASK
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
                "SUCCESS" -> AWAITING_REVIEW
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
                    env.sleep(env.config.ciFailureRetryMinutes, TimeUnit.MINUTES)
                    this
                }
                "CONFLICT" -> {
                    env.sleep(env.config.pollingIntervalSeconds, TimeUnit.SECONDS)
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
                    env.sleep(env.config.pollingIntervalSeconds, TimeUnit.SECONDS)
                    this
                }
            }
        }
    }

    data object AWAITING_REVIEW : OrchestratorState {
        override val name = "AWAITING_REVIEW"
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
            val prNumber = slot.prNumber ?: throw IllegalStateException("prNumber is null")
            if (env.gitHubClient.isPrMerged(prNumber)) {
                env.println("🎉 PR #$prNumber merged! resolving issue locally...")
                return RESOLVE_TASK
            }

            val currentSha = env.gitHubClient.getPrHeadSha(prNumber)
            if (currentSha != slot.lastHeadSha) {
                env.gitHubClient.clearPrCache(prNumber)
            }

            if (handleRebaseAndConflicts(env, slot, prNumber)) {
                env.sleep(env.config.pollingIntervalSeconds, TimeUnit.SECONDS)
                return CI_RUNNING
            }

            val buildStatus = env.gitHubClient.checkBuildStatus(prNumber)

            val issueId = slot.currentIssueId
            val sessionId = slot.julesSessionId
            if (issueId != null) {
                val session = env.julesClient.getActiveSession(issueId)
                val isFailed = if (session != null) {
                    val stat = session.status.lowercase()
                    stat == "failed" || stat == "cancelled" || env.julesClient.hasUnableToCompleteActivity(session.id)
                } else {
                    sessionId != null && env.julesClient.hasUnableToCompleteActivity(sessionId)
                }

                if (isFailed) {
                    val statText = session?.status ?: "FAILED"
                    env.println("\n⚠️ [RETRY] Jules session failed during review: $statText (or has unable to complete activity). Retrying...")
                    env.sendNotification("⚠️ *Jules session failed* during review on PR #$prNumber. Sending 'Retry' message to Jules.")
                    val targetSessionId = session?.id ?: sessionId ?: throw IllegalStateException("session ID is null")
                    env.julesClient.sendSessionMessage(targetSessionId, "Retry")
                    slot.lastReviewedSha = null
                    
                    var retriedSession = env.julesClient.getActiveSession(issueId)
                    var retryWaitAttempts = 0
                    while (retriedSession != null && 
                           (retriedSession.status.lowercase() == "failed" || retriedSession.status.lowercase() == "cancelled" || env.julesClient.hasUnableToCompleteActivity(retriedSession.id)) &&
                           retryWaitAttempts < 15) {
                        env.println("Waiting for Jules session status to transition out of failure state (attempt ${retryWaitAttempts + 1}/15)...")
                        env.sleep(20, TimeUnit.SECONDS)
                        retriedSession = env.julesClient.getActiveSession(issueId)
                        retryWaitAttempts++
                    }
                    return this
                }

                if (session != null && session.status.lowercase() == "in_progress") {
                    env.println("Jules session ${session.id} is actively running (IN_PROGRESS) in AWAITING_REVIEW. Waiting...")
                    env.sleep(env.config.pollingIntervalSeconds, TimeUnit.SECONDS)
                    return this
                }
            }

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
                    return AWAITING_MERGE
                } else {
                    slot.julesReviewPushCount = 0
                    env.println("🟢 Jules pushed a non-empty commit on PR #$prNumber. Treating as real problem resolution. Resetting push count and returning to CI_RUNNING.")
                    return CI_RUNNING
                }
            }

            if (buildStatus != "SUCCESS") {
                return CI_RUNNING
            }

            if (currentSha != slot.lastReviewedSha) {
                val comments = env.gitHubClient.getPrComments(prNumber)
                val searchSha = slot.lastRequestedReviewSha ?: currentSha
                val shaPrefix = searchSha.take(7)

                val requestComment = comments.firstOrNull {
                    (it.body.contains("@jules")) &&
                    it.body.contains(shaPrefix)
                }

                if (requestComment == null) {
                    val currentShaPrefix = currentSha.take(7)
                    env.println("🤖 PR #$prNumber Build Passed. Requesting Jules review for SHA: $currentSha")

                    // If Jules already pushed once instead of reviewing, use a stronger framing.
                    val pushWarning = if (slot.julesReviewPushCount > 0) {
                        "\n\n🚨 **IMPORTANT — PREVIOUS ATTEMPT PUSHED CODE**: Your previous review attempt " +
                        "resulted in a commit push instead of a comment. This is incorrect. " +
                        "You must NOT push anything. Read the instructions below carefully before acting."
                    } else ""

                    val prompt = OrchestratorPrompts.reviewPrompt(prNumber, currentShaPrefix, pushWarning)

                    env.gitHubClient.commentOnPr(prNumber, prompt)
                    slot.lastRequestedReviewSha = currentSha // Record the requested SHA
                    env.sleep(env.config.pollingIntervalSeconds, TimeUnit.SECONDS)
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
                        return AWAITING_MERGE
                    } else {
                        env.println("⌛ Waiting for Jules (@jules) to complete review on PR #$prNumber (SHA: $shaPrefix)...")
                        env.sleep(env.config.pollingIntervalSeconds, TimeUnit.SECONDS)
                        return this
                    }
                }
            }
            return AWAITING_MERGE
        }
    }

    data object AWAITING_MERGE : OrchestratorState {
        override val name = "AWAITING_MERGE"
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
            val prNumber = slot.prNumber ?: throw IllegalStateException("prNumber is null")

            if (env.gitHubClient.isPrMerged(prNumber)) {
                env.println("🎉 PR #$prNumber merged! resolving issue locally...")
                return RESOLVE_TASK
            }

            val currentSha = env.gitHubClient.getPrHeadSha(prNumber)
            if (currentSha != slot.lastHeadSha) {
                return CI_RUNNING
            }

            val status = env.gitHubClient.checkBuildStatus(prNumber)
            if (status != "SUCCESS") {
                return CI_RUNNING
            }

            val now = System.currentTimeMillis()
            if (now - slot.lastWaitingLogTime > 600_000) {
                val prUrl = env.gitHubClient.getPrUrl(prNumber)
                env.println("⌛ Waiting for manual merge of PR #$prNumber at: $prUrl")
                env.sendNotification("⌛ Waiting for manual merge of PR #$prNumber at: $prUrl")
                slot.lastWaitingLogTime = now
            }
            env.sleep(env.config.pollingIntervalSeconds, TimeUnit.SECONDS)
            return this
        }
    }

    fun isTaskTimedOut(slot: SlotContext, config: OrchestratorConfig): Boolean {
        if (slot.startTime == 0L) return false
        val now = System.currentTimeMillis()
        val elapsedMinutes = TimeUnit.MILLISECONDS.toMinutes(now - slot.startTime)
        return elapsedMinutes >= config.taskTimeoutThresholdMinutes
    }

    data object RESOLVE_TASK : OrchestratorState {
        override val name = "RESOLVE_TASK"
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
            val issueId = slot.currentIssueId
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
            return SELECT_TASK
        }
    }

    companion object {
        fun fromName(name: String?): OrchestratorState {
            return when (name) {
                "SELECT_TASK" -> SELECT_TASK
                "PENDING_APPROVAL" -> PENDING_APPROVAL
                "AWAITING_JULES_START" -> AWAITING_JULES_START
                "AWAITING_PR" -> AWAITING_PR
                "CI_RUNNING" -> CI_RUNNING
                "AWAITING_REVIEW" -> AWAITING_REVIEW
                "AWAITING_MERGE" -> AWAITING_MERGE
                "RESOLVE_TASK" -> RESOLVE_TASK
                // Compatibility with old enum names
                "AWAIT_START_APPROVAL" -> PENDING_APPROVAL
                "AWAIT_JULES_START" -> AWAITING_JULES_START
                "AWAIT_PR_CREATION" -> AWAITING_PR
                "MONITOR_PR" -> CI_RUNNING
                else -> SELECT_TASK
            }
        }
    }
}

private fun handleRebaseAndConflicts(env: OrchestratorEnvironment, slot: SlotContext, prNumber: String): Boolean {
    var status = env.gitHubClient.getPrMergeStatus(prNumber)
    if (status.isError) {
        env.println("⚠️ Error retrieving PR merge status: ${status.errorMessage}. Retrying status retrieval...")
        if (status.isAuthError()) {
            env.sendNotification("🚨 *GitHub CLI Authentication/Query Failure on PR #$prNumber!* Error: ${status.errorMessage}")
        }

        var attempts = 1
        while (status.isError && attempts < 3) {
            env.sleep(2, TimeUnit.SECONDS)
            attempts++
            env.println("🔄 Retrying PR merge status retrieval (attempt $attempts/3)...")
            status = env.gitHubClient.getPrMergeStatus(prNumber)
        }

        if (status.isError) {
            env.println("❌ Failed to retrieve PR merge status after retries. Aborting current check iteration and waiting.")
            return true
        }
    }

    val isBehind = status.behindBy > 0
    val isConflicting = status.mergeable == "CONFLICTING"

    if (isBehind || isConflicting) {
        val reason = if (isConflicting) "conflict status" else "behind master by ${status.behindBy} commits"
        env.println("🔄 Active PR #$prNumber is $reason. Attempting automated merge of master into branch...")
        val rebaseResult = env.gitHubClient.mergeMasterIntoBranch(prNumber)
        val rebaseSuccess = rebaseResult.success
        if (rebaseSuccess) {
            env.println("✅ Successfully auto-merged master into PR #$prNumber.")
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
