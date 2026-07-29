package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.Properties

class OrchestratorContextFieldsTest {

    @Test
    fun `save and load properties with missing values`() {
        val context = OrchestratorContext()
        val props = Properties()
        context.save(props)

        val newContext = OrchestratorContext()
        newContext.load(props)

        assertNull(newContext.currentIssueId)
        assertNull(newContext.currentIssueTitle)
        assertNull(newContext.currentIssueFile)
        assertNull(newContext.githubIssueNumber)
        assertNull(newContext.julesSessionId)
        assertNull(newContext.prNumber)
        assertNull(newContext.lastHeadSha)
        assertNull(newContext.lastReviewedSha)
        assertNull(newContext.lastBuildStatus)
        assertNull(newContext.lastCheckedSha)
        assertEquals(0L, newContext.lastWaitingLogTime)
        assertEquals(0L, newContext.lastStatusChangeTime)
        assertNull(newContext.lastKnownStatus)
        assertEquals(0L, newContext.lastPendingNotificationTime)
        assertNull(newContext.lastFailedSha)
        assertEquals(0L, newContext.startTime)
        assertEquals(0, newContext.julesRetries)
        assertEquals(0, newContext.julesReviewPushCount)
        assertEquals(0, newContext.julesReviewAttemptCount)
        assertTrue(newContext.state is SelectTaskState)
    }

    @Test
    fun `save and load all properties`() {
        val context = OrchestratorContext()
        context.state = CiRunningState("id-1", "gh-1", "j-1", "pr-1")
        context.currentIssueId = "id-1"
        context.currentIssueTitle = "title-1"
        context.currentIssueFile = "file-1.md"
        context.githubIssueNumber = "gh-1"
        context.julesSessionId = "j-1"
        context.prNumber = "pr-1"
        context.skippedIds.add("skip-1")

        context.lastHeadSha = "sha-1"
        context.lastReviewedSha = "rev-1"
        context.lastBuildStatus = "SUCCESS"
        context.lastCheckedSha = "chk-1"
        context.lastWaitingLogTime = 100L
        context.lastStatusChangeTime = 200L
        context.lastKnownStatus = "KNOWN"
        context.lastPendingNotificationTime = 300L
        context.lastFailedSha = "fail-1"
        context.startTime = 400L
        context.julesRetries = 5
        context.julesReviewPushCount = 6
        context.julesReviewAttemptCount = 7

        val props = Properties()
        context.save(props)

        val newContext = OrchestratorContext()
        newContext.load(props)

        val s = newContext.state
        assertTrue(s is CiRunningState)
        assertEquals("id-1", s.issueId)
        assertEquals("gh-1", s.githubIssueNumber)
        assertEquals("j-1", s.julesSessionId)
        assertEquals("pr-1", s.prNumber)

        assertEquals("id-1", newContext.currentIssueId)
        assertEquals("title-1", newContext.currentIssueTitle)
        assertEquals("file-1.md", newContext.currentIssueFile)
        assertEquals("gh-1", newContext.githubIssueNumber)
        assertEquals("j-1", newContext.julesSessionId)
        assertEquals("pr-1", newContext.prNumber)
        assertEquals(true, newContext.skippedIds.contains("skip-1"))

        assertEquals("sha-1", newContext.lastHeadSha)
        assertEquals("rev-1", newContext.lastReviewedSha)
        assertEquals("SUCCESS", newContext.lastBuildStatus)
        assertEquals("chk-1", newContext.lastCheckedSha)
        assertEquals(100L, newContext.lastWaitingLogTime)
        assertEquals(200L, newContext.lastStatusChangeTime)
        assertEquals("KNOWN", newContext.lastKnownStatus)
        assertEquals(300L, newContext.lastPendingNotificationTime)
        assertEquals("fail-1", newContext.lastFailedSha)
        assertEquals(400L, newContext.startTime)
        assertEquals(5, newContext.julesRetries)
        assertEquals(6, newContext.julesReviewPushCount)
        assertEquals(7, newContext.julesReviewAttemptCount)
    }

    @Test
    fun `save and load slot context properties`() {
        val slot = SlotContext("slot-1")
        slot.state = AwaitingReviewState("slot-1", "gh-1", "j-1", "pr-1", "sha-1")
        slot.githubIssueNumber = "gh-1"
        slot.julesSessionId = "j-1"
        slot.prNumber = "pr-1"
        slot.lastHeadSha = "sha-1"
        slot.julesRetries = 2
        slot.julesReviewPushCount = 1
        slot.julesReviewAttemptCount = 3

        val props = Properties()
        slot.save(props, "slot.slot-1")

        val loadedSlot = SlotContext("slot-1")
        loadedSlot.load(props, "slot.slot-1")

        val s = loadedSlot.state
        assertTrue(s is AwaitingReviewState)
        assertEquals("slot-1", s.issueId)
        assertEquals("gh-1", s.githubIssueNumber)
        assertEquals("j-1", s.julesSessionId)
        assertEquals("pr-1", s.prNumber)
        assertEquals("sha-1", s.lastHeadSha)

        assertEquals(2, loadedSlot.julesRetries)
        assertEquals(1, loadedSlot.julesReviewPushCount)
        assertEquals(3, loadedSlot.julesReviewAttemptCount)
    }
}
