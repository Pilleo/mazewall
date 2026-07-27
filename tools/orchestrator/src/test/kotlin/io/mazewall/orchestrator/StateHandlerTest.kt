package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

class MockOrchestratorEnvironment : OrchestratorEnvironment {
    override val config = OrchestratorConfig()
    val printlns = mutableListOf<String>()
    var approved = true
    var existingIssueNumber: String? = null
    var createdIssueNumber = "123"
    var issueClosed = false
    var prClosed = false
    var linkedPrNumber: String? = null
    var prMerged = false
    var prHeadSha = "sha123"
    var buildStatus = "SUCCESS"
    val prComments = mutableListOf<GitHubComment>()
    val commentedPrs = mutableListOf<Pair<String, String>>()
    var julesSession: JulesSession? = null
    val sentJulesMessages = mutableListOf<Pair<String, String>>()
    val issues = mutableListOf<BacklogIssue>()
    val resolvedIssues = mutableListOf<BacklogIssue>()
    var mapsRegenerated = false
    var stateFileDeleted = false
    var sleepCount = 0
    val notifications = mutableListOf<String>()
    var bellRungCount = 0
    var isCommitEmptyResult = false
    var prMergeStatus = PrMergeStatus("MERGEABLE", 0)
    var clearPrCacheCount = 0
    var rebaseBranchResult = RebaseResult(true, 0, emptyList())

    override fun println(message: Any?) { printlns.add(message.toString()) }
    override fun print(message: Any?) {}
    override fun errPrintln(message: Any?) {}
    override fun sleep(duration: Long, unit: TimeUnit) { sleepCount++ }
    override fun ringBell(times: Int) { bellRungCount += times }
    override fun readLine(): String? = if (approved) "y" else "n"
    override fun getEnvOrNull(key: String): String? = null

    override fun sendNotification(message: String) { notifications.add(message) }
    override fun requestApproval(issueId: String, text: String): Boolean = approved


    var hasUnableToCompleteActivity: Boolean = false


    override val gitHubClient = object : GitHubClient {
        override fun getPrMergeStatus(prNumber: String): PrMergeStatus = prMergeStatus
        override fun findExistingIssueNumber(issueId: String): String? = existingIssueNumber
        override fun createIssue(title: String, body: String, label: String): String = createdIssueNumber
        override fun isIssueClosed(issueNumber: String): Boolean = issueClosed
        override fun isPrClosed(prNumber: String): Boolean = prClosed
        override fun findLinkedPR(issueNumber: String, issueId: String, julesSessionId: String?): String? = linkedPrNumber
        override fun isPrMerged(prNumber: String): Boolean = prMerged
        override fun getPrHeadSha(prNumber: String): String = prHeadSha
        override fun checkBuildStatus(prNumber: String): String = buildStatus
        override fun getPrComments(prNumber: String): List<GitHubComment> = prComments
        override fun commentOnPr(prNumber: String, body: String) { commentedPrs.add(prNumber to body) }
        override fun commentOnIssue(issueNumber: String, body: String) {}
        override fun getPrDiff(prNumber: String): String = "mock diff"
        override fun getFailedBuildLogs(prNumber: String): String = "mock failed logs"
        override fun getPrUrl(prNumber: String): String = "mock url"
        override fun isCommitEmpty(prNumber: String, shaOld: String, shaNew: String): Boolean = isCommitEmptyResult
        override fun rebaseBranch(prNumber: String): RebaseResult = rebaseBranchResult
        override fun clearPrCache(prNumber: String) { clearPrCacheCount++ }
    }

    override val julesClient = object : JulesClient {
        override fun getActiveSession(issueId: String): JulesSession? = julesSession
        override fun getSessionStatusFromActivities(sessionId: String): String? = julesSession?.status
        override fun hasUnableToCompleteActivity(sessionId: String): Boolean = hasUnableToCompleteActivity
        override fun triggerSession(repo: String, issueId: String, prompt: String) {}
        override fun sendSessionMessage(sessionId: String, prompt: String) { sentJulesMessages.add(sessionId to prompt) }
        override fun listSessions(): List<JulesSession> = emptyList()
    }

    override fun parseAllIssues(): List<BacklogIssue> = issues
    override fun writeGithubIssue(issue: BacklogIssue, number: Int) {}
    override fun removeGithubIssue(issue: BacklogIssue) {}
    override fun markIssueAsResolved(issue: BacklogIssue) { resolvedIssues.add(issue) }
    override fun deleteStateFile() { stateFileDeleted = true }
    override fun generateKnowledgeMap() { mapsRegenerated = true }
}

class StateHandlerTest {

    @Test
    fun testSelectTaskTransitionsToPendingApproval() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext()
        val issue = BacklogIssue(File("test.md"), "issue-1", "Title", 1, "open", emptyList())
        env.issues.add(issue)

        val nextState = OrchestratorState.SELECT_TASK.execute(env, context)

        assertEquals(OrchestratorState.PENDING_APPROVAL, nextState)
        assertEquals("issue-1", context.currentIssueId)
    }

    @Test
    fun testPendingApprovalTransitionsToAwaitingJulesStart() {
        val tempFile = File.createTempFile("issue-1", ".md")
        tempFile.writeText("Test issue body")
        try {
            val env = MockOrchestratorEnvironment()
            val context = OrchestratorContext().apply {
                currentIssueId = "issue-1"
                currentIssueTitle = "Title"
                currentIssueFile = tempFile.absolutePath
            }
            env.issues.add(BacklogIssue(tempFile, "issue-1", "Title", 1, "open", emptyList()))

            val nextState = OrchestratorState.PENDING_APPROVAL.execute(env, context)

            assertEquals(OrchestratorState.AWAITING_JULES_START, nextState)
            assertEquals("123", context.githubIssueNumber)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testAwaitingJulesStartTransitionsToAwaitingPr() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
        }
        env.julesSession = JulesSession("s1", "desc", "repo", "status")

        val nextState = OrchestratorState.AWAITING_JULES_START.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_PR, nextState)
        assertEquals("s1", context.julesSessionId)
    }

    @Test
    fun testAwaitingPrTransitionsToCiRunning() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            julesSessionId = "s1"
        }
        env.linkedPrNumber = "pr-1"

        val nextState = OrchestratorState.AWAITING_PR.execute(env, context)

        assertEquals(OrchestratorState.CI_RUNNING, nextState)
        assertEquals("pr-1", context.prNumber)
    }

    @Test
    fun testCiRunningTransitionsToAwaitingReviewOnSuccess() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            currentIssueId = "issue-1"
        }
        env.buildStatus = "SUCCESS"

        val nextState = OrchestratorState.CI_RUNNING.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_REVIEW, nextState)
    }

    @Test
    fun testAwaitingReviewRequestsReview() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            lastHeadSha = "sha123"
        }
        env.prHeadSha = "sha123"
        env.buildStatus = "SUCCESS"

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_REVIEW, nextState)
        assertTrue(env.commentedPrs.any { it.second.contains("@jules You are acting as a **code reviewer**") })
    }

    @Test
    fun testAwaitingReviewTransitionsToAwaitingMergeAfterJulesReply() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            lastHeadSha = "sha123"
        }
        env.prHeadSha = "sha123"
        env.buildStatus = "SUCCESS"
        env.prComments.add(GitHubComment(GitHubCommentAuthor("user"), "@jules sha123", "2023-01-01T00:00:00Z"))
        env.prComments.add(GitHubComment(GitHubCommentAuthor("jules"), "Approved", "2023-01-01T00:01:00Z"))

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_MERGE, nextState)
        assertEquals("sha123", context.lastReviewedSha)
    }

    @Test
    fun testAwaitingMergeTransitionsToResolveTaskOnMerge() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            lastHeadSha = "sha123"
        }
        env.prHeadSha = "sha123"
        env.buildStatus = "SUCCESS"
        env.prMerged = true

        val nextState = OrchestratorState.AWAITING_MERGE.execute(env, context)

        assertEquals(OrchestratorState.RESOLVE_TASK, nextState)
    }

    @Test
    fun testResolveTaskTransitionsToSelectTask() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
        }
        env.issues.add(BacklogIssue(File("test.md"), "issue-1", "Title", 1, "open", emptyList()))

        val nextState = OrchestratorState.RESOLVE_TASK.execute(env, context)

        assertEquals(OrchestratorState.SELECT_TASK, nextState)
        assertNull(context.currentIssueId)
        assertTrue(env.stateFileDeleted)
        assertTrue(env.mapsRegenerated)
    }

    @Test
    fun testCiRunningNotifiesWhenStuckInPending() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            currentIssueId = "issue-1"
            lastHeadSha = "sha123"
        }
        env.prHeadSha = "sha123"
        env.buildStatus = "PENDING"

        // First execution sets the initial status
        OrchestratorState.CI_RUNNING.execute(env, context)
        assertEquals("PENDING", context.lastKnownStatus)
        assertEquals(0, env.notifications.size)

        // Mock passage of time
        context.lastStatusChangeTime = System.currentTimeMillis() - 1_000_000 // > 900_000 threshold

        // Second execution should trigger notification
        OrchestratorState.CI_RUNNING.execute(env, context)
        assertEquals(1, env.notifications.size)
        assertTrue(env.notifications[0].contains("stuck in PENDING"))
        assertEquals(1, env.bellRungCount)

        // Third execution should not trigger notification again
        OrchestratorState.CI_RUNNING.execute(env, context)
        assertEquals(1, env.notifications.size)
    }

    @Test
    fun testPendingApprovalResolvesClosedIssue() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            currentIssueTitle = "Title"
            githubIssueNumber = "123"
        }
        env.issueClosed = true
        env.issues.add(BacklogIssue(File("test.md"), "issue-1", "Title", 1, "open", emptyList()))

        val nextState = OrchestratorState.PENDING_APPROVAL.execute(env, context)

        assertEquals(OrchestratorState.SELECT_TASK, nextState)
        assertNull(context.currentIssueId)
        assertTrue(env.resolvedIssues.any { it.id == "issue-1" })
    }

    @Test
    fun testAwaitingJulesStartResolvesClosedIssue() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
        }
        env.issueClosed = true
        env.issues.add(BacklogIssue(File("test.md"), "issue-1", "Title", 1, "open", emptyList()))

        val nextState = OrchestratorState.AWAITING_JULES_START.execute(env, context)

        assertEquals(OrchestratorState.SELECT_TASK, nextState)
        assertNull(context.currentIssueId)
        assertTrue(env.resolvedIssues.any { it.id == "issue-1" })
    }

    @Test
    fun testCiRunningRetriesOnJulesSessionFailure() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            julesSessionId = "s1"
        }
        env.prHeadSha = "sha123"
        env.buildStatus = "PENDING"
        env.julesSession = JulesSession("s1", "description", "repo", "FAILED")

        val nextState = OrchestratorState.CI_RUNNING.execute(env, context)

        assertEquals(OrchestratorState.CI_RUNNING, nextState)
        assertEquals(1, context.julesRetries)
        assertEquals("s1", context.julesSessionId)
        assertEquals("pr-1", context.prNumber)
        assertTrue(env.sentJulesMessages.any { it.first == "s1" && it.second == "Retry" })
    }

    @Test
    fun testAwaitingReviewRetriesOnJulesSessionFailure() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            julesSessionId = "s1"
            lastReviewedSha = "sha123"
        }
        env.prHeadSha = "sha123"
        env.buildStatus = "SUCCESS"
        env.julesSession = JulesSession("s1", "description", "repo", "FAILED")

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_REVIEW, nextState)
        assertNull(context.lastReviewedSha)
        assertTrue(env.sentJulesMessages.any { it.first == "s1" && it.second == "Retry" })
    }

    @Test
    fun testAwaitingPrRetriesOnJulesSessionFailure() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            julesSessionId = "s1"
        }
        env.julesSession = JulesSession("s1", "description", "repo", "FAILED")

        val nextState = OrchestratorState.AWAITING_PR.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_PR, nextState)
        assertEquals(1, context.julesRetries)
        assertEquals("s1", context.julesSessionId)
        assertTrue(env.sentJulesMessages.any { it.first == "s1" && it.second == "Retry" })
    }

    @Test
    fun testAwaitingReviewEmptyCommitEscalatesToHuman() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            lastHeadSha = "sha123"
            julesReviewPushCount = 1
        }
        env.prHeadSha = "sha456"
        env.isCommitEmptyResult = true

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_MERGE, nextState)
        assertEquals("sha456", context.lastHeadSha)
        assertTrue(env.notifications.any { it == "⚠️ Jules pushed an empty commit during review phase on PR #pr-1" })
        assertTrue(env.commentedPrs.isEmpty(), "No correction comment should be sent for empty commits")
    }

    @Test
    fun testAwaitingReviewNonEmptyCommitReturnsToCiRunning() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            lastHeadSha = "sha123"
            julesReviewPushCount = 1
        }
        env.prHeadSha = "sha456"
        env.isCommitEmptyResult = false

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        assertEquals(OrchestratorState.CI_RUNNING, nextState)
        assertEquals("sha456", context.lastHeadSha)
        assertEquals(0, context.julesReviewPushCount)
        assertTrue(env.commentedPrs.isEmpty(), "No correction comment should be sent for non-empty commits")
    }

    @Test
    fun testCiRunningDetectsBehindPrAndRebases() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            currentIssueId = "issue-1"
        }
        env.prMergeStatus = PrMergeStatus("MERGEABLE", 5) // behind by 5 commits
        env.buildStatus = "PENDING"

        val nextState = OrchestratorState.CI_RUNNING.execute(env, context)

        // It should stay in CI_RUNNING after attempting rebase
        assertEquals(OrchestratorState.CI_RUNNING, nextState)
        // Check if sleep was triggered (which happens after handleRebaseAndConflicts returns true)
        assertTrue(env.sleepCount > 0, "Should sleep after triggering rebase")
    }

    @Test
    fun testAwaitingPrDetectsConflictingPrAndRebases() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            julesSessionId = "s1"
        }
        env.linkedPrNumber = "pr-1"
        env.prMergeStatus = PrMergeStatus("CONFLICTING", 0)

        val nextState = OrchestratorState.AWAITING_PR.execute(env, context)

        // It should transition to CI_RUNNING
        assertEquals(OrchestratorState.CI_RUNNING, nextState)
        // Check if sleep was triggered during the rebase workflow
        assertTrue(env.sleepCount > 0, "Should sleep after triggering rebase")
    }

    @Test
    fun testAwaitingReviewDetectsConflictingPrAndRebases() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            currentIssueId = "issue-1"
        }
        env.prMergeStatus = PrMergeStatus("CONFLICTING", 0)
        env.buildStatus = "SUCCESS"

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        // It should transition to CI_RUNNING on successful rebase
        assertEquals(OrchestratorState.CI_RUNNING, nextState)
        // Check if sleep was triggered during the rebase workflow
        assertTrue(env.sleepCount > 0, "Should sleep after triggering rebase")
    }

    @Test
    fun testHandleRebaseAndConflictsHandlesAuthErrorAndAlerts() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            currentIssueId = "issue-1"
        }
        env.prMergeStatus = PrMergeStatus("UNKNOWN", 0, isError = true, errorMessage = "HTTP 401: Bad credentials")
        env.buildStatus = "SUCCESS"

        val nextState = OrchestratorState.CI_RUNNING.execute(env, context)

        // It should stay in CI_RUNNING
        assertEquals(OrchestratorState.CI_RUNNING, nextState)
        // Verify notification was sent
        assertTrue(env.notifications.any { it.contains("GitHub CLI Authentication/Query Failure") || it.contains("HTTP 401: Bad credentials") },
            "Notification should be sent for authentication error")
        // Verify we retried status retrieval 3 times (the first call + 2 retries = 3 calls total)
        // Total sleep count should be 3 (2 for retry delays, 1 for polling interval sleep inside CI_RUNNING because handleRebaseAndConflicts returned true)
        assertEquals(3, env.sleepCount, "Should sleep for retries and polling interval")
    }

    @Test
    fun testAwaitingPrIssueClosedResolvesTask() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
        }
        env.issueClosed = true
        val issue = BacklogIssue(File("test.md"), "issue-1", "Title", 1, "open", emptyList())
        env.issues.add(issue)

        val nextState = OrchestratorState.AWAITING_PR.execute(env, context)

        assertEquals(OrchestratorState.SELECT_TASK, nextState)
        assertTrue(env.resolvedIssues.contains(issue))
        assertTrue(context.skippedIds.contains("issue-1"))
    }

    @Test
    fun testAwaitingPrJulesSessionFailedExceedsRetryLimit() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            julesSessionId = "s1"
            julesRetries = 2
        }
        env.julesSession = JulesSession("s1", "desc", "repo", "FAILED")
        val issue = BacklogIssue(File("test.md"), "issue-1", "Title", 1, "open", emptyList())
        env.issues.add(issue)

        val nextState = OrchestratorState.AWAITING_PR.execute(env, context)

        assertEquals(OrchestratorState.SELECT_TASK, nextState)
        assertTrue(context.skippedIds.contains("issue-1"))
    }

    @Test
    fun testAwaitingPrCompletedReviewTask() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "review-task-1"
            githubIssueNumber = "123"
            julesSessionId = "s1"
        }
        env.julesSession = JulesSession("s1", "desc", "repo", "Completed")
        val issue = BacklogIssue(File("test.md"), "review-task-1", "Title", 1, "open", emptyList())
        env.issues.add(issue)

        val nextState = OrchestratorState.AWAITING_PR.execute(env, context)

        assertEquals(OrchestratorState.SELECT_TASK, nextState)
        assertTrue(env.resolvedIssues.contains(issue))
    }

    @Test
    fun testAwaitingPrFeedbackNeededNotifies() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            julesSessionId = "s1"
        }
        env.julesSession = JulesSession("s1", "desc", "repo", "Awaiting Feedback")

        val nextState = OrchestratorState.AWAITING_PR.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_PR, nextState)
        assertTrue(env.notifications.any { it.contains("Jules needs feedback") })
        assertEquals(5, env.bellRungCount)
    }

    @Test
    fun testCiRunningSessionFailedWithRetry() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            prNumber = "pr-1"
            julesSessionId = "s1"
            julesRetries = 0
        }
        // Fail but wait until state transition out of failure
        env.julesSession = JulesSession("s1", "desc", "repo", "FAILED")

        val nextState = OrchestratorState.CI_RUNNING.execute(env, context)

        assertEquals(OrchestratorState.CI_RUNNING, nextState)
        assertEquals(1, context.julesRetries)
    }

    @Test
    fun testCiRunningSessionFailedExceedsRetryLimit() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            prNumber = "pr-1"
            julesSessionId = "s1"
            julesRetries = 2
        }
        env.julesSession = JulesSession("s1", "desc", "repo", "FAILED")
        val issue = BacklogIssue(File("test.md"), "issue-1", "Title", 1, "open", emptyList())
        env.issues.add(issue)

        val nextState = OrchestratorState.CI_RUNNING.execute(env, context)

        assertEquals(OrchestratorState.SELECT_TASK, nextState)
        assertTrue(context.skippedIds.contains("issue-1"))
    }

    @Test
    fun testCiRunningSessionInProgressWaits() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            prNumber = "pr-1"
            julesSessionId = "s1"
        }
        env.julesSession = JulesSession("s1", "desc", "repo", "in_progress")

        val nextState = OrchestratorState.CI_RUNNING.execute(env, context)

        assertEquals(OrchestratorState.CI_RUNNING, nextState)
        assertTrue(env.sleepCount > 0)
    }

    @Test
    fun testCiRunningPrMergedResolvesTask() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            prNumber = "pr-1"
        }
        env.prMerged = true

        val nextState = OrchestratorState.CI_RUNNING.execute(env, context)

        assertEquals(OrchestratorState.RESOLVE_TASK, nextState)
    }

    @Test
    fun testCiRunningBuildFailedSendsLogsFeedback() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            prNumber = "pr-1"
            lastHeadSha = "sha123"
        }
        env.prHeadSha = "sha123"
        env.buildStatus = "FAILURE"

        val nextState = OrchestratorState.CI_RUNNING.execute(env, context)

        assertEquals(OrchestratorState.CI_RUNNING, nextState)
        assertTrue(env.commentedPrs.any { it.first == "pr-1" && it.second.contains("CI Build Failed") })
        assertEquals("sha123", context.lastFailedSha)
    }

    @Test
    fun testCiRunningBuildFailedAlreadyNotifiedDoesNotRepeat() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            prNumber = "pr-1"
            lastHeadSha = "sha123"
            lastFailedSha = "sha123"
        }
        env.prHeadSha = "sha123"
        env.buildStatus = "FAILURE"

        val nextState = OrchestratorState.CI_RUNNING.execute(env, context)

        assertEquals(OrchestratorState.CI_RUNNING, nextState)
        assertTrue(env.commentedPrs.isEmpty())
    }

    @Test
    fun testAwaitingReviewPrMerged() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
        }
        env.prMerged = true

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        assertEquals(OrchestratorState.RESOLVE_TASK, nextState)
    }

    @Test
    fun testAwaitingReviewSessionFailedWithRetry() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            prNumber = "pr-1"
            julesSessionId = "s1"
        }
        env.julesSession = JulesSession("s1", "desc", "repo", "FAILED")

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_REVIEW, nextState)
        assertNull(context.lastReviewedSha)
    }

    @Test
    fun testAwaitingReviewSessionInProgress() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
            prNumber = "pr-1"
            julesSessionId = "s1"
        }
        env.julesSession = JulesSession("s1", "desc", "repo", "in_progress")

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_REVIEW, nextState)
        assertTrue(env.sleepCount > 0)
    }

    @Test
    fun testAwaitingReviewNoReviewRequestedYet() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            lastHeadSha = "sha123"
        }
        env.prHeadSha = "sha123"
        env.buildStatus = "SUCCESS"

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_REVIEW, nextState)
        assertTrue(env.commentedPrs.any { it.first == "pr-1" && it.second.contains("@jules") })
    }

    @Test
    fun testAwaitingReviewNoReviewRequestedYetPreviousWarning() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            lastHeadSha = "sha123"
            julesReviewPushCount = 1
        }
        env.prHeadSha = "sha123"
        env.buildStatus = "SUCCESS"

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_REVIEW, nextState)
        assertTrue(env.commentedPrs.any { it.first == "pr-1" && it.second.contains("PREVIOUS ATTEMPT PUSHED CODE") })
    }

    @Test
    fun testAwaitingReviewWaitingForJulesReply() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            lastHeadSha = "sha123"
        }
        env.prHeadSha = "sha123"
        env.buildStatus = "SUCCESS"
        env.prComments.add(GitHubComment(GitHubCommentAuthor("user"), "@jules sha123", "2023-01-01T00:00:00Z"))

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_REVIEW, nextState)
        assertTrue(env.sleepCount > 0)
    }

    @Test
    fun testAwaitingReviewJulesReplyWithVerdictApproved() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            lastHeadSha = "sha123"
        }
        env.prHeadSha = "sha123"
        env.buildStatus = "SUCCESS"
        env.prComments.add(GitHubComment(GitHubCommentAuthor("user"), "@jules sha123", "2023-01-01T00:00:00Z"))
        env.prComments.add(GitHubComment(GitHubCommentAuthor("jules"), "VERDICT: APPROVED", "2023-01-01T00:01:00Z"))

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_MERGE, nextState)
        assertEquals("sha123", context.lastReviewedSha)
    }

    @Test
    fun testAwaitingReviewJulesReplyWithOtherVerdicts() {
        val verdicts = listOf("VERDICT: NEEDS_CHANGES", "VERDICT: UNCERTAIN", "NO_VERDICT")
        for (v in verdicts) {
            val env = MockOrchestratorEnvironment()
            val context = OrchestratorContext().apply {
                prNumber = "pr-1"
                lastHeadSha = "sha123"
            }
            env.prHeadSha = "sha123"
            env.buildStatus = "SUCCESS"
            env.prComments.add(GitHubComment(GitHubCommentAuthor("user"), "@jules sha123", "2023-01-01T00:00:00Z"))
            env.prComments.add(GitHubComment(GitHubCommentAuthor("jules"), v, "2023-01-01T00:01:00Z"))

            val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

            assertEquals(OrchestratorState.AWAITING_MERGE, nextState)
        }
    }

    @Test
    fun testAwaitingMergePrMerged() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
        }
        env.prMerged = true

        val nextState = OrchestratorState.AWAITING_MERGE.execute(env, context)

        assertEquals(OrchestratorState.RESOLVE_TASK, nextState)
    }

    @Test
    fun testAwaitingMergeNewCommitsDetected() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            lastHeadSha = "sha123"
        }
        env.prHeadSha = "sha456"

        val nextState = OrchestratorState.AWAITING_MERGE.execute(env, context)

        assertEquals(OrchestratorState.CI_RUNNING, nextState)
    }

    @Test
    fun testAwaitingMergeBuildStatusFails() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            lastHeadSha = "sha123"
        }
        env.prHeadSha = "sha123"
        env.buildStatus = "FAILURE"

        val nextState = OrchestratorState.AWAITING_MERGE.execute(env, context)

        assertEquals(OrchestratorState.CI_RUNNING, nextState)
    }

    @Test
    fun testAwaitingMergeManualMergeLogTime() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            lastHeadSha = "sha123"
            lastWaitingLogTime = System.currentTimeMillis() - 700_000
        }
        env.prHeadSha = "sha123"
        env.buildStatus = "SUCCESS"

        val nextState = OrchestratorState.AWAITING_MERGE.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_MERGE, nextState)
        assertTrue(env.notifications.any { it.contains("Waiting for manual merge") })
    }

    @Test
    fun testSelectTaskWithNoCandidates() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext()

        val nextState = OrchestratorState.SELECT_TASK.execute(env, context)

        assertEquals(OrchestratorState.SELECT_TASK, nextState)
    }

    @Test
    fun testSelectTaskClearsSkippedWhenNoUnblockedTasksAvailable() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            skippedIds.add("issue-1")
        }
        val issue = BacklogIssue(File("test.md"), "issue-1", "Title", 1, "open", emptyList())
        env.issues.add(issue)

        val nextState = OrchestratorState.SELECT_TASK.execute(env, context)

        assertEquals(OrchestratorState.PENDING_APPROVAL, nextState)
        assertTrue(context.skippedIds.isEmpty())
    }

    @Test
    fun testCacheInvalidationOnShaChange() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            lastHeadSha = "sha123"
        }
        env.prHeadSha = "sha456" // new commits!

        OrchestratorState.CI_RUNNING.execute(env, context)

        // It should have cleared the PR cache once
        assertEquals(1, env.clearPrCacheCount)
    }

    @Test
    fun testMergeBaseDiffApplyReconstruction() {
        val tempDir = java.nio.file.Files.createTempDirectory("test-git-rebase").toFile()
        try {
            fun runGit(vararg command: String): String {
                val pb = ProcessBuilder(*command)
                pb.directory(tempDir)
                pb.redirectErrorStream(true)
                val process = pb.start()
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                if (process.exitValue() != 0) {
                    throw RuntimeException("Command '${command.joinToString(" ")}' failed with exit code ${process.exitValue()}: $output")
                }
                return output
            }

            // 1. Init git repo
            runGit("git", "init")
            runGit("git", "config", "user.name", "Test User")
            runGit("git", "config", "user.email", "test@example.com")
            // Set initial branch to master (older git versions use master, newer might use main)
            try {
                runGit("git", "checkout", "-b", "master")
            } catch (_: Exception) {}

            // 2. Create initial file on master and commit
            val initialFile = File(tempDir, "initial.txt")
            initialFile.writeText("initial content")
            runGit("git", "add", "initial.txt")
            runGit("git", "commit", "-m", "initial commit")

            // 3. Create branch and commit a new file (simulating Jules's PR branch)
            runGit("git", "checkout", "-b", "jules-branch")
            val julesFile = File(tempDir, "jules_work.txt")
            julesFile.writeText("jules content")
            runGit("git", "add", "jules_work.txt")
            runGit("git", "commit", "-m", "jules commit")

            // 4. Switch back to master and add a master-only file (added after Jules diverged)
            runGit("git", "checkout", "master")
            val masterFile = File(tempDir, "master_only.txt")
            masterFile.writeText("master content")
            runGit("git", "add", "master_only.txt")
            runGit("git", "commit", "-m", "master commit")

            // At this point, the repository looks like this:
            // master has: initial.txt, master_only.txt
            // jules-branch has: initial.txt, jules_work.txt (stale: lacks master_only.txt)

            // Let's find the merge-base between master and jules-branch
            val mergeBase = runGit("git", "merge-base", "master", "jules-branch").trim()
            assertFalse(mergeBase.isEmpty())

            // Compute Jules's NET diff (merge-base -> jules-branch)
            val diffText = runGit("git", "diff", mergeBase, "jules-branch")

            // Create a worktree/clean directory of master
            val worktreeDir = File(tempDir, "worktree-clean")
            worktreeDir.mkdirs()
            runGit("git", "worktree", "add", worktreeDir.absolutePath, "master", "--detach")

            // Inside worktreeDir, write the patch and apply it
            val patchFile = File(worktreeDir, "patch.diff")
            patchFile.writeText(diffText)

            val pbApply = ProcessBuilder("git", "apply", "--3way", "patch.diff")
            pbApply.directory(worktreeDir)
            val pApply = pbApply.start()
            pApply.waitFor()
            assertEquals(0, pApply.exitValue(), "git apply --3way should succeed")

            patchFile.delete()

            // Verify that master_only.txt exists and was NOT deleted in the worktree
            val masterOnlyInWorktree = File(worktreeDir, "master_only.txt")
            assertTrue(masterOnlyInWorktree.exists(), "master_only.txt should NOT be deleted!")
            assertEquals("master content", masterOnlyInWorktree.readText().trim())

            // Verify that jules_work.txt exists in the worktree
            val julesWorkInWorktree = File(worktreeDir, "jules_work.txt")
            assertTrue(julesWorkInWorktree.exists(), "jules_work.txt should exist!")
            assertEquals("jules content", julesWorkInWorktree.readText().trim())

            // Clean up worktree
            runGit("git", "worktree", "remove", worktreeDir.absolutePath, "--force")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
