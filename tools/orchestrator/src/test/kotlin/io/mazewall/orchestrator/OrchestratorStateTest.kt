package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

class OrchestratorStateTest {

    @Test
    fun `fromName resolves correctly`() {
        assertTrue(OrchestratorState.fromName("SELECT_TASK") is SelectTaskState)
        assertTrue(OrchestratorState.fromName("AWAITING_JULES_START") is AwaitingJulesStartState)
        assertTrue(OrchestratorState.fromName("PENDING_APPROVAL") is PendingApprovalState)
        assertTrue(OrchestratorState.fromName("AWAITING_PR") is AwaitingPrState)
        assertTrue(OrchestratorState.fromName("CI_RUNNING") is CiRunningState)
        assertTrue(OrchestratorState.fromName("AWAITING_REVIEW") is AwaitingReviewState)
        assertTrue(OrchestratorState.fromName("AWAITING_MERGE") is AwaitingMergeState)
        assertTrue(OrchestratorState.fromName("RESOLVE_TASK") is ResolveTaskState)
    }

    @Test
    fun `fromName resolves legacy names correctly`() {
        assertTrue(OrchestratorState.fromName("AWAIT_START_APPROVAL") is PendingApprovalState)
        assertTrue(OrchestratorState.fromName("AWAIT_JULES_START") is AwaitingJulesStartState)
        assertTrue(OrchestratorState.fromName("AWAIT_PR_CREATION") is AwaitingPrState)
        assertTrue(OrchestratorState.fromName("MONITOR_PR") is CiRunningState)
        assertTrue(OrchestratorState.fromName(null) is SelectTaskState)
        assertTrue(OrchestratorState.fromName("UNKNOWN") is SelectTaskState)
    }

    @Test
    fun `fromSlot resolves all states correctly`() {
        val slot = SlotContext("issue-1").apply {
            currentIssueTitle = "Title"
            currentIssueFile = "test.md"
            githubIssueNumber = "123"
            julesSessionId = "s123"
            prNumber = "pr123"
            lastHeadSha = "sha123"
        }

        assertTrue(OrchestratorState.fromSlot(slot, "SELECT_TASK") is SelectTaskState)

        val pending = OrchestratorState.fromSlot(slot, "PENDING_APPROVAL") as PendingApprovalState
        assertEquals("issue-1", pending.issueId)
        assertEquals("Title", pending.issueTitle)
        assertEquals("test.md", pending.issueFile)
        assertEquals("123", pending.githubIssueNumber)

        val awaitingJules = OrchestratorState.fromSlot(slot, "AWAITING_JULES_START") as AwaitingJulesStartState
        assertEquals("issue-1", awaitingJules.issueId)
        assertEquals("123", awaitingJules.githubIssueNumber)

        val awaitingPr = OrchestratorState.fromSlot(slot, "AWAITING_PR") as AwaitingPrState
        assertEquals("issue-1", awaitingPr.issueId)
        assertEquals("123", awaitingPr.githubIssueNumber)
        assertEquals("s123", awaitingPr.julesSessionId)

        val ciRunning = OrchestratorState.fromSlot(slot, "CI_RUNNING") as CiRunningState
        assertEquals("issue-1", ciRunning.issueId)
        assertEquals("123", ciRunning.githubIssueNumber)
        assertEquals("s123", ciRunning.julesSessionId)
        assertEquals("pr123", ciRunning.prNumber)

        val awaitingReview = OrchestratorState.fromSlot(slot, "AWAITING_REVIEW") as AwaitingReviewState
        assertEquals("issue-1", awaitingReview.issueId)
        assertEquals("123", awaitingReview.githubIssueNumber)
        assertEquals("s123", awaitingReview.julesSessionId)
        assertEquals("pr123", awaitingReview.prNumber)
        assertEquals("sha123", awaitingReview.lastHeadSha)

        val awaitingMerge = OrchestratorState.fromSlot(slot, "AWAITING_MERGE") as AwaitingMergeState
        assertEquals("issue-1", awaitingMerge.issueId)
        assertEquals("123", awaitingMerge.githubIssueNumber)
        assertEquals("s123", awaitingMerge.julesSessionId)
        assertEquals("pr123", awaitingMerge.prNumber)
        assertEquals("sha123", awaitingMerge.lastHeadSha)

        val resolve = OrchestratorState.fromSlot(slot, "RESOLVE_TASK") as ResolveTaskState
        assertEquals("issue-1", resolve.issueId)

        // Null stateName uses slot state name
        slot.state = SelectTaskState
        assertTrue(OrchestratorState.fromSlot(slot) is SelectTaskState)
    }

    @Test
    fun `fromSlot throws exception for missing properties`() {
        val slot = SlotContext("issue-1")
        assertFailsWith<IllegalStateException> { OrchestratorState.fromSlot(slot, "AWAITING_JULES_START") }
        assertFailsWith<IllegalStateException> { OrchestratorState.fromSlot(slot, "AWAITING_PR") }
        assertFailsWith<IllegalStateException> { OrchestratorState.fromSlot(slot, "CI_RUNNING") }
        assertFailsWith<IllegalStateException> { OrchestratorState.fromSlot(slot, "AWAITING_REVIEW") }
        assertFailsWith<IllegalStateException> { OrchestratorState.fromSlot(slot, "AWAITING_MERGE") }
    }

    @Test
    fun `fromContext resolves all states with fallbacks`() {
        val context = OrchestratorContext()
        assertTrue(OrchestratorState.fromContext(context, "SELECT_TASK") is SelectTaskState)

        val pending = OrchestratorState.fromContext(context, "PENDING_APPROVAL") as PendingApprovalState
        assertEquals("dummy-issue-id", pending.issueId)
        assertEquals("Unknown Title", pending.issueTitle)
        assertEquals("", pending.issueFile)
        assertNull(pending.githubIssueNumber)

        val awaitingJules = OrchestratorState.fromContext(context, "AWAITING_JULES_START") as AwaitingJulesStartState
        assertEquals("dummy-issue-id", awaitingJules.issueId)
        assertEquals("dummy-github-issue", awaitingJules.githubIssueNumber)

        val awaitingPr = OrchestratorState.fromContext(context, "AWAITING_PR") as AwaitingPrState
        assertEquals("dummy-issue-id", awaitingPr.issueId)
        assertEquals("dummy-github-issue", awaitingPr.githubIssueNumber)
        assertEquals("dummy-session-id", awaitingPr.julesSessionId)

        val ciRunning = OrchestratorState.fromContext(context, "CI_RUNNING") as CiRunningState
        assertEquals("dummy-issue-id", ciRunning.issueId)
        assertEquals("dummy-github-issue", ciRunning.githubIssueNumber)
        assertEquals("dummy-session-id", ciRunning.julesSessionId)
        assertEquals("dummy-pr-number", ciRunning.prNumber)

        val awaitingReview = OrchestratorState.fromContext(context, "AWAITING_REVIEW") as AwaitingReviewState
        assertEquals("dummy-issue-id", awaitingReview.issueId)
        assertEquals("dummy-github-issue", awaitingReview.githubIssueNumber)
        assertEquals("dummy-session-id", awaitingReview.julesSessionId)
        assertEquals("dummy-pr-number", awaitingReview.prNumber)
        assertEquals("dummy-sha", awaitingReview.lastHeadSha)

        val awaitingMerge = OrchestratorState.fromContext(context, "AWAITING_MERGE") as AwaitingMergeState
        assertEquals("dummy-issue-id", awaitingMerge.issueId)
        assertEquals("dummy-github-issue", awaitingMerge.githubIssueNumber)
        assertEquals("dummy-session-id", awaitingMerge.julesSessionId)
        assertEquals("dummy-pr-number", awaitingMerge.prNumber)
        assertEquals("dummy-sha", awaitingMerge.lastHeadSha)

        val resolve = OrchestratorState.fromContext(context, "RESOLVE_TASK") as ResolveTaskState
        assertEquals("dummy-issue-id", resolve.issueId)

        // Custom properties set
        context.currentIssueId = "id-1"
        context.currentIssueTitle = "Title"
        context.currentIssueFile = "file.md"
        context.githubIssueNumber = "123"
        context.julesSessionId = "s123"
        context.prNumber = "pr123"
        context.lastHeadSha = "sha123"

        val pendingCustom = OrchestratorState.fromContext(context, "PENDING_APPROVAL") as PendingApprovalState
        assertEquals("id-1", pendingCustom.issueId)
        assertEquals("Title", pendingCustom.issueTitle)
        assertEquals("file.md", pendingCustom.issueFile)
        assertEquals("123", pendingCustom.githubIssueNumber)
    }

    @Test
    fun `isTaskTimedOut logic works correctly`() {
        val slot = SlotContext("issue-1")
        val config = OrchestratorConfig(taskTimeoutThresholdMinutes = 10L)

        // Start time 0 -> not timed out
        slot.startTime = 0L
        assertFalse(isTaskTimedOut(slot, config))

        // Start time now -> not timed out
        slot.startTime = System.currentTimeMillis()
        assertFalse(isTaskTimedOut(slot, config))

        // Start time 20 minutes ago -> timed out
        slot.startTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(20)
        assertTrue(isTaskTimedOut(slot, config))
    }

    @Test
    fun `updateSlot maps state properties correctly`() {
        val slot = SlotContext("id-test")

        SelectTaskState.updateSlot(slot)
        assertEquals("id-test", slot.currentIssueId)

        val pending = PendingApprovalState("id-1", "Title-1", "file-1.md", "gh-1")
        pending.updateSlot(slot)
        assertEquals("id-1", slot.currentIssueId)
        assertEquals("Title-1", slot.currentIssueTitle)
        assertEquals("file-1.md", slot.currentIssueFile)
        assertEquals("gh-1", slot.githubIssueNumber)

        val awaitingJules = AwaitingJulesStartState("id-2", "gh-2")
        awaitingJules.updateSlot(slot)
        assertEquals("id-2", slot.currentIssueId)
        assertEquals("gh-2", slot.githubIssueNumber)

        val awaitingPr = AwaitingPrState("id-3", "gh-3", "s-3")
        awaitingPr.updateSlot(slot)
        assertEquals("id-3", slot.currentIssueId)
        assertEquals("gh-3", slot.githubIssueNumber)
        assertEquals("s-3", slot.julesSessionId)

        val ci = CiRunningState("id-4", "gh-4", "s-4", "pr-4")
        ci.updateSlot(slot)
        assertEquals("id-4", slot.currentIssueId)
        assertEquals("gh-4", slot.githubIssueNumber)
        assertEquals("s-4", slot.julesSessionId)
        assertEquals("pr-4", slot.prNumber)

        val review = AwaitingReviewState("id-5", "gh-5", "s-5", "pr-5", "sha-5")
        review.updateSlot(slot)
        assertEquals("id-5", slot.currentIssueId)
        assertEquals("gh-5", slot.githubIssueNumber)
        assertEquals("s-5", slot.julesSessionId)
        assertEquals("pr-5", slot.prNumber)
        assertEquals("sha-5", slot.lastHeadSha)

        val merge = AwaitingMergeState("id-6", "gh-6", "s-6", "pr-6", "sha-6")
        merge.updateSlot(slot)
        assertEquals("id-6", slot.currentIssueId)
        assertEquals("gh-6", slot.githubIssueNumber)
        assertEquals("s-6", slot.julesSessionId)
        assertEquals("pr-6", slot.prNumber)
        assertEquals("sha-6", slot.lastHeadSha)

        val resolve = ResolveTaskState("id-7")
        resolve.updateSlot(slot)
        assertEquals("id-7", slot.currentIssueId)
    }

    @Test
    fun `data class methods of state classes are verified`() {
        val s1 = PendingApprovalState("id", "title", "file", "gh")
        val s2 = PendingApprovalState("id", "title", "file", "gh")
        assertEquals(s1, s2)
        assertEquals(s1.hashCode(), s2.hashCode())
        assertTrue(s1.toString().contains("PendingApprovalState"))

        val s3 = AwaitingJulesStartState("id", "gh")
        val s4 = AwaitingJulesStartState("id", "gh")
        assertEquals(s3, s4)
        assertEquals(s3.hashCode(), s4.hashCode())

        val s5 = AwaitingPrState("id", "gh", "s")
        val s6 = AwaitingPrState("id", "gh", "s")
        assertEquals(s5, s6)
        assertEquals(s5.hashCode(), s6.hashCode())

        val s7 = CiRunningState("id", "gh", "s", "pr")
        val s8 = CiRunningState("id", "gh", "s", "pr")
        assertEquals(s7, s8)
        assertEquals(s7.hashCode(), s8.hashCode())

        val s9 = AwaitingReviewState("id", "gh", "s", "pr", "sha")
        val s10 = AwaitingReviewState("id", "gh", "s", "pr", "sha")
        assertEquals(s9, s10)
        assertEquals(s9.hashCode(), s10.hashCode())

        val s11 = AwaitingMergeState("id", "gh", "s", "pr", "sha")
        val s12 = AwaitingMergeState("id", "gh", "s", "pr", "sha")
        assertEquals(s11, s12)
        assertEquals(s11.hashCode(), s12.hashCode())

        val s13 = ResolveTaskState("id")
        val s14 = ResolveTaskState("id")
        assertEquals(s13, s14)
        assertEquals(s13.hashCode(), s14.hashCode())
    }
}
