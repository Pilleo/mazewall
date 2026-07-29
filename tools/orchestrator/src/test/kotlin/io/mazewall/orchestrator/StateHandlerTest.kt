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
    var mergeMasterIntoBranchResult = RebaseResult(true, 0, emptyList())
    var mergeMasterIntoBranchCallCount = 0

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
        override fun mergeMasterIntoBranch(prNumber: String, sessionId: String?, targetFiles: List<String>): RebaseResult {
            mergeMasterIntoBranchCallCount++
            return mergeMasterIntoBranchResult
        }
        override fun approveRescue(prNumber: String, rescueBranchName: String) {}
        override fun clearPrCache(prNumber: String) { clearPrCacheCount++ }
    }

    override val julesClient = object : JulesClient {
        override fun getActiveSession(issueId: String): JulesSession? = julesSession
        override fun getSessionStatusFromActivities(sessionId: String): String? = julesSession?.status
        override fun hasUnableToCompleteActivity(sessionId: String): Boolean = hasUnableToCompleteActivity
        override fun triggerSession(repo: String, issueId: String, prompt: String) {}
        override fun sendSessionMessage(sessionId: String, prompt: String) { sentJulesMessages.add(sessionId to prompt) }
        override fun listSessions(): List<JulesSession> = emptyList()

        override fun getSessionPatch(sessionId: String): String? {
            return null
        }

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

        var nextState = OrchestratorState.AWAITING_PR.execute(env, context)

        // It should transition to CI_RUNNING
        assertEquals(OrchestratorState.CI_RUNNING, nextState)

        // Now execute CI_RUNNING state to trigger the merge handling
        nextState = nextState.execute(env, context)
        assertEquals(OrchestratorState.CI_RUNNING, nextState)

        // Check if sleep was triggered during the merge workflow
        assertTrue(env.sleepCount > 0, "Should sleep after triggering merge")
    }

    @Test
    fun testAwaitingReviewAutomatedMergePreservesReviewStatus() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            currentIssueId = "issue-1"
            lastHeadSha = "sha_old"
            lastReviewedSha = "sha_old"
            lastRequestedReviewSha = "sha_old"
        }
        env.prMergeStatus = PrMergeStatus("MERGEABLE", 1) // Behind by 1 commit
        env.buildStatus = "SUCCESS"
        env.prHeadSha = "sha_new" // The new head after merge
        env.mergeMasterIntoBranchResult = RebaseResult(true, 0) // Merge succeeds!

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        // It should transition to CI_RUNNING to verify the build of the merge commit
        assertEquals(OrchestratorState.CI_RUNNING, nextState)
        // Verify that slot properties were successfully updated to the new SHA!
        assertEquals("sha_new", context.lastHeadSha)
        assertEquals("sha_new", context.lastReviewedSha)
        assertEquals("sha_new", context.lastRequestedReviewSha)
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
    fun testMergeMasterIntoBranchReconstruction() {
        val tempDir = java.nio.file.Files.createTempDirectory("test-git-merge").toFile()
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

            fun runGitInDir(dir: File, vararg command: String): String {
                val pb = ProcessBuilder(*command)
                pb.directory(dir)
                pb.redirectErrorStream(true)
                val process = pb.start()
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                if (process.exitValue() != 0) {
                    throw RuntimeException("Command '${command.joinToString(" ")}' failed in ${dir.name} with exit code ${process.exitValue()}: $output")
                }
                return output
            }

            // 1. Init git repo
            runGit("git", "init", "--template=")
            runGit("git", "config", "user.name", "Test User")
            runGit("git", "config", "user.email", "test@example.com")
            try {
                runGit("git", "checkout", "-b", "master")
            } catch (_: Exception) {}

            // 2. Create initial file on master and commit
            val initialFile = File(tempDir, "initial.txt")
            initialFile.writeText("initial content")
            runGit("git", "add", "initial.txt")
            runGit("git", "commit", "--no-verify", "-m", "initial commit")

            // 3. Create PR branch and commit changes
            runGit("git", "checkout", "-b", "jules-branch")
            val julesFile = File(tempDir, "jules_work.txt")
            julesFile.writeText("jules content")
            runGit("git", "add", "jules_work.txt")
            runGit("git", "commit", "--no-verify", "-m", "jules commit 1")

            // 4. Switch back to master and add/modify master-only files (non-conflicting)
            runGit("git", "checkout", "master")
            val masterFile = File(tempDir, "master_only.txt")
            masterFile.writeText("master content")
            runGit("git", "add", "master_only.txt")
            runGit("git", "commit", "--no-verify", "-m", "master commit 1")

            // 5. Simulate our mergeMasterIntoBranch behavior using an isolated worktree
            val worktreeDir = File(tempDir, "worktree-merge")
            worktreeDir.mkdirs()
            runGit("git", "worktree", "add", worktreeDir.absolutePath, "jules-branch", "--detach")

            // Merge master into the branch inside the worktree
            runGitInDir(worktreeDir, "git", "merge", "master", "--no-edit", "-m", "chore: merge master into jules-branch")

            // Verify both files exist and are correctly merged
            val mergedJulesFile = File(worktreeDir, "jules_work.txt")
            assertTrue(mergedJulesFile.exists())
            assertEquals("jules content", mergedJulesFile.readText().trim())

            val mergedMasterFile = File(worktreeDir, "master_only.txt")
            assertTrue(mergedMasterFile.exists())
            assertEquals("master content", mergedMasterFile.readText().trim())

            // Verify commit history is preserved and contains the merge commit (2 parents)
            val parentCount = runGitInDir(worktreeDir, "git", "rev-list", "--parents", "-n", "1", "HEAD")
            val parents = parentCount.split(" ").filter { it.isNotBlank() }
            assertTrue(parents.size >= 3, "Merge commit should have at least 2 parents")

            // Clean up worktree
            runGit("git", "worktree", "remove", worktreeDir.absolutePath, "--force")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testMergeMasterIntoBranchConflictReconstruction() {
        val tempDir = java.nio.file.Files.createTempDirectory("test-git-merge-conflict").toFile()
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

            fun runGitInDir(dir: File, vararg command: String): String {
                val pb = ProcessBuilder(*command)
                pb.directory(dir)
                pb.redirectErrorStream(true)
                val process = pb.start()
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                return output
            }

            // Init git repo
            runGit("git", "init", "--template=")
            runGit("git", "config", "user.name", "Test User")
            runGit("git", "config", "user.email", "test@example.com")
            try {
                runGit("git", "checkout", "-b", "master")
            } catch (_: Exception) {}

            // Create initial file on master
            val initialFile = File(tempDir, "initial.txt")
            initialFile.writeText("initial content")
            runGit("git", "add", "initial.txt")
            runGit("git", "commit", "--no-verify", "-m", "initial commit")

            // Create PR branch and modify initial.txt
            runGit("git", "checkout", "-b", "jules-branch")
            initialFile.writeText("jules modified initial.txt")
            runGit("git", "add", "initial.txt")
            runGit("git", "commit", "--no-verify", "-m", "jules commit 1")

            // Switch to master and modify initial.txt (causing conflict)
            runGit("git", "checkout", "master")
            initialFile.writeText("master modified initial.txt differently")
            runGit("git", "add", "initial.txt")
            runGit("git", "commit", "--no-verify", "-m", "master commit 1")

            // Create worktree on jules-branch
            val worktreeDir = File(tempDir, "worktree-conflict")
            worktreeDir.mkdirs()
            runGit("git", "worktree", "add", worktreeDir.absolutePath, "jules-branch", "--detach")

            // Merge master into the branch, expecting failure/conflict
            val pbMerge = ProcessBuilder("git", "merge", "master", "--no-edit")
            pbMerge.directory(worktreeDir)
            pbMerge.redirectErrorStream(true)
            val processMerge = pbMerge.start()
            val outputMerge = processMerge.inputStream.bufferedReader().readText().trim()
            processMerge.waitFor()

            // It should exit with non-zero code due to merge conflict
            assertNotEquals(0, processMerge.exitValue())
            assertTrue(outputMerge.contains("CONFLICT"), "Output should mention CONFLICT")

            // Abort the merge
            runGitInDir(worktreeDir, "git", "merge", "--abort")

            // Clean up worktree
            runGit("git", "worktree", "remove", worktreeDir.absolutePath, "--force")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testMergeMasterIntoBranchAlreadyUpToDateReconstruction() {
        val tempDir = java.nio.file.Files.createTempDirectory("test-git-merge-up-to-date").toFile()
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

            fun runGitInDir(dir: File, vararg command: String): String {
                val pb = ProcessBuilder(*command)
                pb.directory(dir)
                pb.redirectErrorStream(true)
                val process = pb.start()
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                if (process.exitValue() != 0) {
                    throw RuntimeException("Command '${command.joinToString(" ")}' failed in ${dir.name} with exit code ${process.exitValue()}: $output")
                }
                return output
            }

            // Init git repo
            runGit("git", "init", "--template=")
            runGit("git", "config", "user.name", "Test User")
            runGit("git", "config", "user.email", "test@example.com")
            try {
                runGit("git", "checkout", "-b", "master")
            } catch (_: Exception) {}

            // Create initial file on master
            val initialFile = File(tempDir, "initial.txt")
            initialFile.writeText("initial content")
            runGit("git", "add", "initial.txt")
            runGit("git", "commit", "--no-verify", "-m", "initial commit")

            // Create PR branch
            runGit("git", "checkout", "-b", "jules-branch")

            // Jules commits some work
            val julesFile = File(tempDir, "jules_work.txt")
            julesFile.writeText("jules content")
            runGit("git", "add", "jules_work.txt")
            runGit("git", "commit", "--no-verify", "-m", "jules commit 1")

            // Create worktree on jules-branch
            val worktreeDir = File(tempDir, "worktree-up-to-date")
            worktreeDir.mkdirs()
            runGit("git", "worktree", "add", worktreeDir.absolutePath, "jules-branch", "--detach")

            // Merge master into the branch inside the worktree
            val mergeOutput = runGitInDir(worktreeDir, "git", "merge", "master", "--no-edit")
            assertTrue(mergeOutput.contains("Already up to date") || mergeOutput.contains("Already up-to-date"), "Should be already up to date")

            // Check how many commits are ahead of master (should be 1 because of jules_work.txt)
            val aheadOfMaster = runGitInDir(worktreeDir, "git", "rev-list", "--count", "master..HEAD").trim().toIntOrNull() ?: 0
            assertEquals(1, aheadOfMaster)

            // Let's checkout master and check how many commits on master are ahead of jules-branch (should be 0)
            val behindMasterCount = runGitInDir(worktreeDir, "git", "rev-list", "--count", "HEAD..master").trim().toIntOrNull() ?: 0
            assertEquals(0, behindMasterCount, "No master commits should be missing from jules-branch")

            // Clean up worktree
            runGit("git", "worktree", "remove", worktreeDir.absolutePath, "--force")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testCiRunningDoesNotMergeWhenJulesSessionInProgress() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
        }
        env.prMergeStatus = PrMergeStatus("MERGEABLE", 5) // Behind by 5 commits
        env.buildStatus = "PENDING"
        env.julesSession = JulesSession("s1", "desc", "repo", "in_progress") // Session is active!

        val nextState = OrchestratorState.CI_RUNNING.execute(env, context)

        // It should stay in CI_RUNNING
        assertEquals(OrchestratorState.CI_RUNNING, nextState)
        // Verify that it did NOT call mergeMasterIntoBranch because the session was active!
        assertEquals(0, env.mergeMasterIntoBranchCallCount)
        // Verify it sleep-waited for the session
        assertTrue(env.sleepCount > 0, "Should sleep-wait for the active session")
    }

    @Test
    fun testAwaitingReviewDoesNotMergeWhenJulesSessionInProgress() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            prNumber = "pr-1"
            currentIssueId = "issue-1"
            githubIssueNumber = "123"
        }
        env.prMergeStatus = PrMergeStatus("MERGEABLE", 5) // Behind by 5 commits
        env.buildStatus = "SUCCESS"
        env.julesSession = JulesSession("s1", "desc", "repo", "in_progress") // Session is active!

        val nextState = OrchestratorState.AWAITING_REVIEW.execute(env, context)

        // It should stay in AWAITING_REVIEW
        assertEquals(OrchestratorState.AWAITING_REVIEW, nextState)
        // Verify that it did NOT call mergeMasterIntoBranch because the session was active!
        assertEquals(0, env.mergeMasterIntoBranchCallCount)
        // Verify it sleep-waited for the session
        assertTrue(env.sleepCount > 0, "Should sleep-wait for the active session")
    }



    @Test
    fun testMergeMasterIntoBranchUnrelatedHistoriesRescue() {
        val tempDir = java.nio.file.Files.createTempDirectory("test-git-unrelated-rescue").toFile()
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

            fun runGitInDir(dir: java.io.File, vararg command: String): String {
                val pb = ProcessBuilder(*command)
                pb.directory(dir)
                pb.redirectErrorStream(true)
                val process = pb.start()
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                if (process.exitValue() != 0) {
                    if (command.contains("merge") && output.contains("unrelated histories")) {
                        throw RuntimeException(output)
                    }
                    throw RuntimeException("Command '${command.joinToString(" ")}' failed in ${dir.name} with exit code ${process.exitValue()}: $output")
                }
                return output
            }

            // Init git repo
            runGit("git", "init", "--template=")
            runGit("git", "config", "user.name", "Test User")
            runGit("git", "config", "user.email", "test@example.com")
            try {
                runGit("git", "checkout", "-b", "master")
            } catch (_: Exception) {}

            // Create initial files on master
            val allowedFile = java.io.File(tempDir, "allowed.txt")
            allowedFile.writeText("allowed initial content")
            val masterOnlyFile = java.io.File(tempDir, "master_only.txt")
            masterOnlyFile.writeText("master content")
            runGit("git", "add", "allowed.txt", "master_only.txt")
            runGit("git", "commit", "--no-verify", "-m", "initial commit")

            // Create a completely unrelated orphaned branch simulating Jules root commit
            runGit("git", "checkout", "--orphan", "jules-branch")
            runGit("git", "rm", "-rf", ".") // Clear index

            // Jules modifies allowed.txt (intended) AND disallowed.txt (unintended reversion/pollution)
            allowedFile.writeText("allowed modified content")
            val disallowedFile = java.io.File(tempDir, "disallowed.txt")
            disallowedFile.writeText("disallowed modified content")
            runGit("git", "add", "allowed.txt", "disallowed.txt")
            runGit("git", "commit", "--no-verify", "-m", "jules changes")

            // Create worktree on jules-branch
            val worktreeDir = java.io.File(tempDir, "worktree-unrelated-rescue")
            worktreeDir.mkdirs()
            runGit("git", "worktree", "add", worktreeDir.absolutePath, "jules-branch", "--detach")

            // Simulate the rescue logic from GitHubCli!
            var thrown = false
            try {
                runGitInDir(worktreeDir, "git", "merge", "master", "--no-edit")
            } catch (e: Exception) {
                if (e.message?.contains("unrelated histories") == true) {
                    thrown = true

                    // 1. Abort
                    try { runGitInDir(worktreeDir, "git", "merge", "--abort") } catch (_: Exception) {}

                    // 2. Diff against origin/master to find everything that differs
                    val allDifferentFiles = runGitInDir(worktreeDir, "git", "diff", "--name-only", "master", "jules-branch")
                        .lines().map { it.trim() }.filter { it.isNotEmpty() }

                    // 3. Reset hard to master
                    runGitInDir(worktreeDir, "git", "reset", "--hard", "master")

                    val targetFiles = listOf("allowed.txt")

                    for (file in allDifferentFiles) {
                        val normalizedFile = file.replace('\\', '/').trim()
                        val isAllowed = normalizedFile.startsWith("docs/internals/backlog/") || targetFiles.any { target ->
                            val normalizedTarget = target.replace('\\', '/').trim().removePrefix(":")
                            if (normalizedFile == normalizedTarget || normalizedFile.endsWith("/$normalizedTarget")) return@any true
                            false
                        }

                        if (isAllowed) {
                            val exists = runGitInDir(worktreeDir, "git", "ls-tree", "-r", "jules-branch", "--name-only")
                                .lines().any { it.trim() == file }
                            if (exists) {
                                runGitInDir(worktreeDir, "git", "checkout", "jules-branch", "--", file)
                                runGitInDir(worktreeDir, "git", "add", file)
                            } else {
                                runGitInDir(worktreeDir, "git", "rm", "--ignore-unmatch", file)
                            }
                        }
                    }

                    runGitInDir(worktreeDir, "git", "commit", "-m", "chore(orchestrator): rescue PR onto master")
                }
            }

            assertTrue(thrown, "Expected unrelated histories exception to trigger rescue logic")

            // Verify that after rescue, allowed.txt is still modified with Jules's changes
            val finalAllowedFile = java.io.File(worktreeDir, "allowed.txt")
            assertEquals("allowed modified content", finalAllowedFile.readText().trim())

            // Verify that master_only.txt is preserved exactly as it was on master
            val finalMasterOnlyFile = java.io.File(worktreeDir, "master_only.txt")
            assertEquals("master content", finalMasterOnlyFile.readText().trim())

            // Verify that disallowed.txt DOES NOT EXIST in the worktree because it was not in targetFiles
            val finalDisallowedFile = java.io.File(worktreeDir, "disallowed.txt")
            assertFalse(finalDisallowedFile.exists(), "disallowed.txt should have been discarded because it was not allowed!")

            // Verify merge base! The new commit should have 'master' as its parent!
            val parents = runGitInDir(worktreeDir, "git", "log", "-n", "1", "--format=%P")
            val masterSha = runGitInDir(worktreeDir, "git", "rev-parse", "master")
            assertTrue(parents.contains(masterSha), "The rescued commit must have master as its parent!")

            // Clean up worktree
            runGit("git", "worktree", "remove", worktreeDir.absolutePath, "--force")
        } finally {
            tempDir.deleteRecursively()
        }
    }
    @Test
    fun testMergeMasterIntoBranchSelfHealingReconstruction() {
        val tempDir = java.nio.file.Files.createTempDirectory("test-git-self-healing").toFile()
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

            fun runGitInDir(dir: File, vararg command: String): String {
                val pb = ProcessBuilder(*command)
                pb.directory(dir)
                pb.redirectErrorStream(true)
                val process = pb.start()
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                if (process.exitValue() != 0) {
                    throw RuntimeException("Command '${command.joinToString(" ")}' failed in ${dir.name} with exit code ${process.exitValue()}: $output")
                }
                return output
            }

            // Init git repo
            runGit("git", "init", "--template=")
            runGit("git", "config", "user.name", "Test User")
            runGit("git", "config", "user.email", "test@example.com")
            try {
                runGit("git", "checkout", "-b", "master")
            } catch (_: Exception) {}

            // Create initial files on master
            val allowedFile = File(tempDir, "allowed.txt")
            allowedFile.writeText("allowed initial content")
            val disallowedFile = File(tempDir, "disallowed.txt")
            disallowedFile.writeText("disallowed initial content")
            runGit("git", "add", "allowed.txt", "disallowed.txt")
            runGit("git", "commit", "--no-verify", "-m", "initial commit")

            // Create PR branch
            runGit("git", "checkout", "-b", "jules-branch")

            // Jules modifies allowed.txt (intended) AND disallowed.txt (unintended reversion/pollution)
            allowedFile.writeText("allowed modified content")
            disallowedFile.writeText("disallowed modified content")
            runGit("git", "add", "allowed.txt", "disallowed.txt")
            runGit("git", "commit", "--no-verify", "-m", "jules changes")

            // Switch to master and make a commit
            runGit("git", "checkout", "master")
            val dummyFile = File(tempDir, "dummy.txt")
            dummyFile.writeText("dummy content")
            runGit("git", "add", "dummy.txt")
            runGit("git", "commit", "--no-verify", "-m", "master changes")

            // Create worktree on jules-branch
            val worktreeDir = File(tempDir, "worktree-self-healing")
            worktreeDir.mkdirs()
            runGit("git", "worktree", "add", worktreeDir.absolutePath, "jules-branch", "--detach")

            // Merge master into the branch inside the worktree
            runGitInDir(worktreeDir, "git", "merge", "master", "--no-edit")

            // Verify both files are currently modified compared to origin/master (which is "master" locally)
            val diffBefore = runGitInDir(worktreeDir, "git", "diff", "--name-only", "master")
            assertTrue(diffBefore.contains("allowed.txt"))
            assertTrue(diffBefore.contains("disallowed.txt"))

            // Now perform our self-healing discard logic!
            // Disallowed files should be checkout from master and staged
            val targetFiles = listOf("allowed.txt")
            val differentFiles = runGitInDir(worktreeDir, "git", "diff", "--name-only", "master").lines().map { it.trim() }.filter { it.isNotEmpty() }
            var cleanedAny = false
            for (file in differentFiles) {
                // Check if file is allowed (mimicking isFileAllowed)
                val normalizedFile = file.replace('\\', '/').trim()
                val isAllowed = normalizedFile.startsWith("docs/internals/backlog/") || targetFiles.any { target ->
                    val normalizedTarget = target.replace('\\', '/').trim().removePrefix(":")
                    if (normalizedFile == normalizedTarget || normalizedFile.endsWith("/$normalizedTarget")) return@any true
                    if (normalizedTarget.contains("/src/main/")) {
                        val testTarget = normalizedTarget
                            .replace("/src/main/", "/src/test/")
                            .replace(".kt", "Test.kt")
                            .replace(".java", "Test.java")
                        if (normalizedFile == testTarget || normalizedFile.endsWith("/$testTarget")) return@any true
                    }
                    false
                }

                if (!isAllowed) {
                    runGitInDir(worktreeDir, "git", "checkout", "master", "--", file)
                    runGitInDir(worktreeDir, "git", "add", file)
                    cleanedAny = true
                }
            }

            if (cleanedAny) {
                runGitInDir(worktreeDir, "git", "commit", "-m", "chore: discard unintended file modifications")
            }

            // Verify that after self-healing, allowed.txt is still modified with Jules's changes
            val finalAllowedFile = File(worktreeDir, "allowed.txt")
            assertEquals("allowed modified content", finalAllowedFile.readText().trim())

            // Verify that disallowed.txt was successfully RESTORED to master version ("disallowed initial content")
            val finalDisallowedFile = File(worktreeDir, "disallowed.txt")
            assertEquals("disallowed initial content", finalDisallowedFile.readText().trim())

            // Verify that disallowed.txt is no longer in the diff vs master!
            val diffAfter = runGitInDir(worktreeDir, "git", "diff", "--name-only", "master")
            assertTrue(diffAfter.contains("allowed.txt"))
            assertFalse(diffAfter.contains("disallowed.txt"), "disallowed.txt should have been restored and removed from the diff vs master!")

            // Clean up worktree
            runGit("git", "worktree", "remove", worktreeDir.absolutePath, "--force")
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
