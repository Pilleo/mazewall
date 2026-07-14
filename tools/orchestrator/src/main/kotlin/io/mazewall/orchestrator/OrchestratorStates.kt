package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit

sealed interface OrchestratorState {
    val name: String
    fun execute(env: OrchestratorEnvironment, context: OrchestratorContext): OrchestratorState

    data object SELECT_TASK : OrchestratorState {
        override val name = "SELECT_TASK"
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext): OrchestratorState {
            val allIssues = env.parseAllIssues()
            env.println("📚 Backlog Status: ${allIssues.count { it.status == "open" }} open issues remaining.")

            val forcedTaskId = env.getEnvOrNull("FORCE_TASK")?.takeIf { it.isNotEmpty() }
            val nextIssue = if (forcedTaskId != null) {
                val forcedIssue = allIssues.firstOrNull { it.id.equals(forcedTaskId, ignoreCase = true) }
                if (forcedIssue == null) {
                    env.errPrintln("⚠️ Forced task '$forcedTaskId' not found in backlog! Falling back to dependency graph.")
                } else {
                    env.println("🎯 Forcing specific task: ${forcedIssue.id} - ${forcedIssue.title}")
                }
                forcedIssue
            } else {
                null
            } ?: run {
                val activeIssues = allIssues.filter { it.id !in context.skippedIds }
                var selected = DependencyGraph.selectNextIssue(activeIssues)
                if (selected == null && context.skippedIds.isNotEmpty()) {
                    env.println("♻️ No unblocked tasks available. Clearing skipped tasks list to retry them.")
                    context.skippedIds.clear()
                    val resetActiveIssues = allIssues.filter { it.id !in context.skippedIds }
                    selected = DependencyGraph.selectNextIssue(resetActiveIssues)
                }
                selected
            }

            if (nextIssue == null) {
                env.println("💤 No unblocked open issues found. Checking again in ${env.config.backlogCheckIntervalMinutes} minutes...")
                env.sleep(env.config.backlogCheckIntervalMinutes, TimeUnit.MINUTES)
                return this
            }

            env.println("\n🎯 Next prioritized task: ${nextIssue.id} - ${nextIssue.title} (Priority: ${nextIssue.priority})")
            context.currentIssueId = nextIssue.id
            context.currentIssueTitle = nextIssue.title
            context.currentIssueFile = nextIssue.file.path
            context.githubIssueNumber = nextIssue.githubIssue?.toString()
            context.julesSessionId = null
            context.prNumber = null
            return PENDING_APPROVAL
        }
    }

    data object PENDING_APPROVAL : OrchestratorState {
        override val name = "PENDING_APPROVAL"
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext): OrchestratorState {
            val issueId = context.currentIssueId ?: throw IllegalStateException("currentIssueId is null")
            val issueTitle = context.currentIssueTitle ?: throw IllegalStateException("currentIssueTitle is null")
            val githubIssueNumber = context.githubIssueNumber

            val approved = if (githubIssueNumber != null) {
                env.println("🔄 Resuming already-in-progress task $issueId (linked to GitHub issue #$githubIssueNumber)...")
                true
            } else {
                env.ringBell(3)
                val issueFile = context.currentIssueFile?.let { File(it) }
                val issue = issueFile?.let { BacklogParser.parseIssueFile(it) }

                val text = if (issue != null) {
                    """
                    🤖 *Approval Request: Start Task ${issue.id}*
                    *Title:* ${issue.title}
                    *Severity:* ${issue.severity ?: "N/A"} | *Effort:* ${issue.effort ?: "N/A"} | *Component:* ${issue.component ?: "N/A"}

                    *Context:*
                    ${issue.context ?: "N/A"}

                    *Needed:*
                    ${issue.needed ?: "N/A"}

                    Please approve or skip in the inline keyboard below.
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
                return SELECT_TASK
            }

            env.println("🚀 Starting task `$issueId`...")
            context.startTime = System.currentTimeMillis()

            // Retrieve or create GitHub issue
            var newGithubIssueNumber = context.githubIssueNumber
            if (newGithubIssueNumber == null) {
                val existingIssueNumber = env.findExistingIssueNumber(issueId)
                if (existingIssueNumber != null) {
                    env.println("♻️ Recovered existing GitHub issue #$existingIssueNumber for $issueId (was missing from backlog file).")
                    newGithubIssueNumber = existingIssueNumber
                } else {
                    env.println("Creating GitHub issue for $issueId...")
                    val issueTitleForGit = "[$issueId] $issueTitle"
                    val issueFile = File(context.currentIssueFile!!)
                    val issueBody = issueFile.readText()
                    val enhancedBody = OrchestratorPrompts.taskPrompt(issueBody)
                    newGithubIssueNumber = env.createIssue(issueTitleForGit, enhancedBody, "jules")
                    env.println("Created GitHub issue #$newGithubIssueNumber")
                }
                // Write it to issue file
                val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                if (nextIssue != null) {
                    env.writeGithubIssue(nextIssue, newGithubIssueNumber.toInt())
                }
                context.githubIssueNumber = newGithubIssueNumber
            }

            return AWAITING_JULES_START
        }
    }

    data object AWAITING_JULES_START : OrchestratorState {
        override val name = "AWAITING_JULES_START"
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext): OrchestratorState {
            val issueId = context.currentIssueId ?: throw IllegalStateException("currentIssueId is null")
            var activeSession = env.getJulesSession(issueId)
            var attempts = 0
            while (activeSession == null && attempts < env.config.julesTriggerAttempts) {
                env.println("Waiting for Jules session to be automatically triggered via GitHub issue label (attempt ${attempts + 1}/${env.config.julesTriggerAttempts})...")
                env.sleep(env.config.julesTriggerIntervalSeconds, TimeUnit.SECONDS)
                activeSession = env.getJulesSession(issueId)
                attempts++
            }

            return if (activeSession != null) {
                env.println("Linked Jules session: ID=${activeSession.id}, Status=${activeSession.status}")
                context.julesSessionId = activeSession.id
                AWAITING_PR
            } else {
                if (isTaskTimedOut(context, env.config)) {
                    env.errPrintln("❌ Task $issueId timed out waiting for Jules session. Returning to SELECT_TASK.")
                    context.clearActiveTask()
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
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext): OrchestratorState {
            val issueId = context.currentIssueId ?: throw IllegalStateException("currentIssueId is null")
            val githubIssueNumber = context.githubIssueNumber ?: throw IllegalStateException("githubIssueNumber is null")
            val sessionId = context.julesSessionId

            if (env.isIssueClosed(githubIssueNumber)) {
                env.println("\n\u001B[1;33m⚠️ GitHub issue #$githubIssueNumber was closed. Canceling task $issueId.\u001B[0m")
                val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                if (nextIssue != null) {
                    env.removeGithubIssue(nextIssue)
                }
                context.skippedIds.add(issueId)
                context.clearActiveTask()
                return SELECT_TASK
            }

            val session = env.getJulesSession(issueId)
            if (session != null) {
                val sessionUrl = "https://jules.google.com/session/${session.id}"
                val status = session.status.lowercase()

                if (status == "failed" || status == "cancelled") {
                    if (context.julesRetries < 2) {
                        context.julesRetries++
                        env.println("\n⚠️ [RETRY] Jules task $issueId failed with status: ${session.status}. Retrying (Attempt ${context.julesRetries}/2)...")
                        env.sendNotification("⚠️ *Jules task failed* for $issueId (Status: ${session.status}). Commenting 'Retry' to trigger a new session (Attempt ${context.julesRetries}/2).")
                        env.commentOnIssue(githubIssueNumber, "Retry")
                        context.julesSessionId = null
                        context.lastBuildStatus = null
                        return AWAITING_JULES_START
                    } else {
                        env.println("\n❌ [FAILED] Jules task $issueId failed with status: ${session.status} after ${context.julesRetries} retries.")
                        env.sendNotification("❌ *Jules task failed* for $issueId (Status: ${session.status}) after ${context.julesRetries} retries. Returning to SELECT_TASK.")
                        val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                        if (nextIssue != null) {
                            env.removeGithubIssue(nextIssue)
                        }
                        context.skippedIds.add(issueId)
                        context.clearActiveTask()
                        return SELECT_TASK
                    }
                }

                if (session.status != context.lastBuildStatus) {
                    env.println("Jules session status changed: ${session.status}")
                    context.lastBuildStatus = session.status

                    if (session.status.contains("Awaiting", ignoreCase = true) || session.status.contains("Feedback", ignoreCase = true)) {
                        val alertMsg = "⚠️ *Jules needs feedback on task $issueId!* Status: `${session.status}`. Please check and respond here: $sessionUrl"
                        env.sendNotification(alertMsg)
                        env.println("\n\u001B[1;31m🔔 [FEEDBACK REQUIRED] Jules is blocked waiting for feedback on task $issueId. Status: ${session.status}\u001B[0m")
                        env.println("👉 Respond here: $sessionUrl")
                        env.ringBell(5)
                    } else if (session.status.equals("Completed", ignoreCase = true)) {
                        env.println("\n\u001B[1;32m🟢 [COMPLETED] Jules task $issueId is Completed! Please review and publish the PR in the UI.\u001B[0m")
                        env.println("👉 Publish PR here: $sessionUrl")
                    }
                }

                if (status == "completed" && env.findLinkedPR(githubIssueNumber, issueId, sessionId) == null) {
                    if (context.julesRetries < 2) {
                        context.julesRetries++
                        env.println("\n⚠️ [RETRY] Jules task $issueId finished as Completed but did not open a PR. Retrying (Attempt ${context.julesRetries}/2)...")
                        env.sendNotification("⚠️ *Jules task finished* for $issueId without creating a PR. Commenting 'Retry' to trigger a new session (Attempt ${context.julesRetries}/2).")
                        env.commentOnIssue(githubIssueNumber, "Retry")
                        context.julesSessionId = null
                        context.lastBuildStatus = null
                        return AWAITING_JULES_START
                    } else {
                        env.println("\n❌ [FAILED] Jules task $issueId finished as Completed but did not open a PR after ${context.julesRetries} retries.")
                        env.sendNotification("❌ *Jules task finished* for $issueId without creating a PR after ${context.julesRetries} retries. Returning to SELECT_TASK.")
                        val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                        if (nextIssue != null) {
                            env.removeGithubIssue(nextIssue)
                        }
                        context.skippedIds.add(issueId)
                        context.clearActiveTask()
                        return SELECT_TASK
                    }
                }
            }

            val prNumber = env.findLinkedPR(githubIssueNumber, issueId, sessionId)
            return if (prNumber != null) {
                env.println("Jules opened PR #$prNumber")
                context.prNumber = prNumber
                context.lastBuildStatus = null
                CI_RUNNING
            } else {
                if (isTaskTimedOut(context, env.config)) {
                    env.errPrintln("❌ Task $issueId timed out after ${env.config.taskTimeoutThresholdMinutes} minutes. Returning to SELECT_TASK.")
                    context.clearActiveTask()
                    return SELECT_TASK
                }
                env.println("Waiting for Jules to open a PR for issue #$githubIssueNumber...")
                env.sleep(env.config.pollingIntervalSeconds, TimeUnit.SECONDS)
                this
            }
        }
    }

    data object CI_RUNNING : OrchestratorState {
        override val name = "CI_RUNNING"
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext): OrchestratorState {
            val prNumber = context.prNumber ?: throw IllegalStateException("prNumber is null")
            val issueId = context.currentIssueId ?: throw IllegalStateException("currentIssueId is null")

            if (env.isPrMerged(prNumber)) {
                env.println("🎉 PR #$prNumber merged! resolving issue locally...")
                return RESOLVE_TASK
            }

            val currentSha = env.getPrHeadSha(prNumber)
            if (currentSha != context.lastHeadSha) {
                env.println("🔄 New commits detected on PR #$prNumber (Head SHA: $currentSha). Checking build status...")
                context.lastHeadSha = currentSha
                context.lastKnownStatus = null
                context.lastStatusChangeTime = 0L
                context.lastPendingNotificationTime = 0L
            }

            val status = env.checkBuildStatus(prNumber)
            if (status != context.lastBuildStatus || currentSha != context.lastCheckedSha) {
                env.println("PR #$prNumber build check: $status")
                context.lastBuildStatus = status
                context.lastCheckedSha = currentSha
            }

            return when (status) {
                "SUCCESS" -> AWAITING_REVIEW
                "FAILURE" -> {
                    val headSha = env.getPrHeadSha(prNumber)
                    if (headSha != context.lastFailedSha) {
                        env.println("❌ Build failed on PR #$prNumber. Fetching logs...")
                        val failedLogs = env.getFailedBuildLogs(prNumber)
                        val feedback = """
                            ❌ **CI Build Failed.**
                            @jules Please review the failing logs and fix the implementation:
 
                            ```
                            $failedLogs
                            ```
                        """.trimIndent()
 
                        env.commentOnPr(prNumber, feedback)
                        env.sendNotification("❌ Build failed on PR #$prNumber. Feedback sent to Jules.")
                        context.lastFailedSha = headSha
                    } else {
                        env.println("❌ Build is still failing on SHA $headSha. Waiting for a new commit...")
                    }
                    env.sleep(env.config.ciFailureRetryMinutes, TimeUnit.MINUTES)
                    this
                }
                "CONFLICT" -> {
                    val now = System.currentTimeMillis()
                    if (now - context.lastWaitingLogTime > 60_000) {
                        val prUrl = env.getPrUrl(prNumber)
                        env.sendNotification("⚠️ *PR #$prNumber has conflicts!* Please resolve them: $prUrl")
                        env.println("\u001B[1;31m🔔 [CONFLICT] PR #$prNumber has conflicts! Please resolve conflicts: $prUrl\u001B[0m")
                        env.ringBell(3)
                        context.lastWaitingLogTime = now
                    }
                    env.sleep(env.config.pollingIntervalSeconds, TimeUnit.SECONDS)
                    this
                }
                else -> {
                    val now = System.currentTimeMillis()
                    if (status != context.lastKnownStatus) {
                        context.lastKnownStatus = status
                        context.lastStatusChangeTime = now
                        context.lastPendingNotificationTime = 0L
                    } else if (now - context.lastStatusChangeTime > env.config.stuckPendingThresholdMs && context.lastPendingNotificationTime == 0L) {
                        val prUrl = env.getPrUrl(prNumber)
                        val msg = "⚠️ *PR #$prNumber build status is stuck in $status!* Please check the runner: $prUrl"
                        env.println("\u001B[1;31m🔔 [STUCK] PR #$prNumber build status is stuck in $status! Please check the runner: $prUrl\u001B[0m")
                        env.sendNotification(msg)
                        env.ringBell(1)
                        context.lastPendingNotificationTime = now
                    }
                    env.sleep(env.config.pollingIntervalSeconds, TimeUnit.SECONDS)
                    this
                }
            }
        }
    }

    data object AWAITING_REVIEW : OrchestratorState {
        override val name = "AWAITING_REVIEW"
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext): OrchestratorState {
            val prNumber = context.prNumber ?: throw IllegalStateException("prNumber is null")
            if (env.isPrMerged(prNumber)) {
                env.println("🎉 PR #$prNumber merged! resolving issue locally...")
                return RESOLVE_TASK
            }

            val currentSha = env.getPrHeadSha(prNumber)

            // If the PR head SHA changed it means Jules pushed a new commit instead of just reviewing.
            if (currentSha != context.lastHeadSha) {
                context.julesReviewPushCount++
                env.println(
                    "⚠️ [REVIEW→PUSH] Jules pushed a new commit on PR #$prNumber during the review phase " +
                    "(push #${context.julesReviewPushCount}). SHA changed: ${context.lastHeadSha?.take(7)} → ${currentSha.take(7)}"
                )

                if (context.julesReviewPushCount >= 2) {
                    // Jules keeps committing instead of reviewing — escalate to human.
                    val prUrl = env.getPrUrl(prNumber)
                    env.sendNotification(
                        "⚠️ *Jules pushed instead of reviewing ${context.julesReviewPushCount}x on PR #$prNumber.* " +
                        "Please manually review and merge (or close) the PR: $prUrl"
                    )
                    env.println("🔔 [ESCALATE] Jules review-push loop escalated to human after ${context.julesReviewPushCount} pushes.")
                    env.ringBell(5)
                    // Treat the latest pushed commit as the candidate; hand off to human.
                    return AWAITING_MERGE
                }

                // Post a correction comment on the PR to re-orient Jules before CI re-runs.
                val correctionComment = """
                    @jules You pushed a new commit instead of leaving a code review comment.
                    
                    ⛔ **STOP. Do NOT push any more commits.**
                    
                    Your task for this PR is **read-only code review only**. The implementation is already 
                    complete. Please do NOT modify any files or push any commits.
                    
                    When CI passes on the latest commit, you will receive a new review request. 
                    At that point, respond ONLY with a comment containing your review findings — no file edits, no commits.
                """.trimIndent()
                env.commentOnPr(prNumber, correctionComment)
                env.sendNotification("⚠️ Jules pushed instead of reviewing on PR #$prNumber. Correction comment sent. Returning to CI_RUNNING.")

                // The new commit needs to go through CI before we review it.
                return CI_RUNNING
            }

            val status = env.checkBuildStatus(prNumber)
            if (status != "SUCCESS") {
                return CI_RUNNING
            }

            if (currentSha != context.lastReviewedSha) {
                val comments = env.getPrComments(prNumber)
                val shaPrefix = currentSha.take(7)

                val requestComment = comments.firstOrNull {
                    (it.body.contains("@jules")) &&
                    it.body.contains(shaPrefix)
                }

                if (requestComment == null) {
                    env.println("🤖 PR #$prNumber Build Passed. Requesting Jules review for SHA: $currentSha")

                    // If Jules already pushed once instead of reviewing, use a stronger framing.
                    val pushWarning = if (context.julesReviewPushCount > 0) {
                        "\n\n🚨 **IMPORTANT — PREVIOUS ATTEMPT PUSHED CODE**: Your previous review attempt " +
                        "resulted in a commit push instead of a comment. This is incorrect. " +
                        "You must NOT push anything. Read the instructions below carefully before acting."
                    } else ""

                    val prompt = OrchestratorPrompts.reviewPrompt(prNumber, shaPrefix, pushWarning)

                    env.commentOnPr(prNumber, prompt)
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
                        val prUrl = env.getPrUrl(prNumber)
                        env.sendNotification("🟢 *Jules reviewed PR #$prNumber!* Verdict: $verdict\nReady for merge: $prUrl")
                        context.lastReviewedSha = currentSha
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
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext): OrchestratorState {
            val prNumber = context.prNumber ?: throw IllegalStateException("prNumber is null")

            if (env.isPrMerged(prNumber)) {
                env.println("🎉 PR #$prNumber merged! resolving issue locally...")
                return RESOLVE_TASK
            }

            val currentSha = env.getPrHeadSha(prNumber)
            if (currentSha != context.lastHeadSha) {
                return CI_RUNNING
            }

            val status = env.checkBuildStatus(prNumber)
            if (status != "SUCCESS") {
                return CI_RUNNING
            }

            val now = System.currentTimeMillis()
            if (now - context.lastWaitingLogTime > 600_000) {
                val prUrl = env.getPrUrl(prNumber)
                env.println("⌛ Waiting for manual merge of PR #$prNumber at: $prUrl")
                env.sendNotification("⌛ Waiting for manual merge of PR #$prNumber at: $prUrl")
                context.lastWaitingLogTime = now
            }
            env.sleep(env.config.pollingIntervalSeconds, TimeUnit.SECONDS)
            return this
        }
    }

    fun isTaskTimedOut(context: OrchestratorContext, config: OrchestratorConfig): Boolean {
        if (context.startTime == 0L) return false
        val now = System.currentTimeMillis()
        val elapsedMinutes = TimeUnit.MILLISECONDS.toMinutes(now - context.startTime)
        return elapsedMinutes >= config.taskTimeoutThresholdMinutes
    }

    data object RESOLVE_TASK : OrchestratorState {
        override val name = "RESOLVE_TASK"
        override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext): OrchestratorState {
            val issueId = context.currentIssueId ?: throw IllegalStateException("currentIssueId is null")
            val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }

            if (nextIssue != null) {
                env.markIssueAsResolved(nextIssue)
            } else {
                env.println("⚠️ Issue `$issueId` not found in active backlog. It may have already been resolved and moved in the merged PR.")
            }

            env.println("Regenerating architectural maps...")
            env.generateKnowledgeMap()
            env.println("✅ Resolved issue `$issueId`. Picking next task...")

            context.clearActiveTask()
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
