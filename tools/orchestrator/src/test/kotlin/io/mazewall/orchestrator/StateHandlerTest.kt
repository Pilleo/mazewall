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
    var linkedPrNumber: String? = null
    var prMerged = false
    var prHeadSha = "sha123"
    var buildStatus = "SUCCESS"
    val prComments = mutableListOf<GitHubComment>()
    val commentedPrs = mutableListOf<Pair<String, String>>()
    var julesSession: JulesSession? = null
    val issues = mutableListOf<BacklogIssue>()
    var mapsRegenerated = false
    var stateFileDeleted = false
    var sleepCount = 0

    override fun println(message: Any?) { printlns.add(message.toString()) }
    override fun print(message: Any?) {}
    override fun errPrintln(message: Any?) {}
    override fun sleep(duration: Long, unit: TimeUnit) { sleepCount++ }
    override fun ringBell(times: Int) {}
    override fun readLine(): String? = if (approved) "y" else "n"
    override fun getEnvOrNull(key: String): String? = null

    override fun sendNotification(message: String) {}
    override fun requestApproval(issueId: String, text: String): Boolean = approved

    override fun findExistingIssueNumber(issueId: String): String? = existingIssueNumber
    override fun createIssue(title: String, bodyFile: File, label: String): String = createdIssueNumber
    override fun isIssueClosed(issueNumber: String): Boolean = issueClosed
    override fun findLinkedPR(issueNumber: String, issueId: String, sessionId: String?): String? = linkedPrNumber
    override fun isPrMerged(prNumber: String): Boolean = prMerged
    override fun getPrHeadSha(prNumber: String): String = prHeadSha
    override fun checkBuildStatus(prNumber: String): String = buildStatus
    override fun getPrComments(prNumber: String): List<GitHubComment> = prComments
    override fun commentOnPr(prNumber: String, body: String) { commentedPrs.add(prNumber to body) }
    override fun commentOnIssue(issueNumber: String, body: String) {}
    override fun getPrDiff(prNumber: String): String = "mock diff"
    override fun getFailedBuildLogs(prNumber: String): String = "logs"
    override fun getPrUrl(prNumber: String): String = "http://pr/$prNumber"

    override fun getJulesSession(issueId: String): JulesSession? = julesSession

    override fun parseAllIssues(): List<BacklogIssue> = issues
    override fun writeGithubIssue(issue: BacklogIssue, number: Int) {}
    override fun removeGithubIssue(issue: BacklogIssue) {}
    override fun markIssueAsResolved(issue: BacklogIssue) {}
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
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext().apply {
            currentIssueId = "issue-1"
            currentIssueTitle = "Title"
            currentIssueFile = "test.md"
        }
        env.issues.add(BacklogIssue(File("test.md"), "issue-1", "Title", 1, "open", emptyList()))

        val nextState = OrchestratorState.PENDING_APPROVAL.execute(env, context)

        assertEquals(OrchestratorState.AWAITING_JULES_START, nextState)
        assertEquals("123", context.githubIssueNumber)
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
        assertTrue(env.commentedPrs.any { it.second.contains("@jules Please perform a critical code review") })
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
}
