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
                    newGithubIssueNumber = env.createIssue(issueTitleForGit, File(context.currentIssueFile!!), "jules")
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

            // If PR head changed, go back to CI_RUNNING
            if (currentSha != context.lastHeadSha) {
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

                    val prDiff = env.getPrDiff(prNumber)
                    val prompt = """
                        @jules Please perform a critical code review on this Pull Request (SHA: $currentSha) as a senior JVM security expert and staff engineer for the `mazewall` project.

                        ⚠️ **CRITICAL INSTRUCTION**: Do NOT modify the workspace files or make any commits. Only analyze the code changes and provide your code review feedback directly in a comment on the PR.

                        Provide a detailed response covering:

                        1. **Overview**: Describe what this PR is doing and its main objectives.
                        2. **Rationale**: Why was the solution implemented in this specific way?
                        3. **Comparison & Alternatives**: Why is this solution better than other designs? What alternatives were considered (or should be considered) and what are their trade-offs?
                        4. **Critical & Security Analysis**:
                           - Evaluate correctness and potential failure modes.
                           - Check for JVM sandboxing bypasses, Landlock or Seccomp filter flaws.
                           - Verify FFM (Foreign Function & Memory) alignment, lifecycle, and memory leak risks.
                           - Analyze concurrency, JVM thread coordination, and Loom virtual thread carrier thread safety (preventing carrier poisoning).
                        5. **Conclusion**: Provide your final recommendation (e.g. Approved or needs changes).

                        Please be concise, extremely precise, and thorough. If you are not sure -say so. Use formatting for readability. Just leave a comment in the PR, do not commit new files!
                    """.trimIndent()

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
                        val prUrl = env.getPrUrl(prNumber)
                        env.sendNotification("🟢 *Jules reviewed PR #$prNumber!* Ready for final manual review and merge: $prUrl")
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
            val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId } ?: throw IllegalStateException("nextIssue not found in backlog")

            env.markIssueAsResolved(nextIssue)

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
