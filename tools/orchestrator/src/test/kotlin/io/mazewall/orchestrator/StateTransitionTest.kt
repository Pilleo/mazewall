package io.mazewall.orchestrator

import java.io.File
import kotlin.test.*

class StateTransitionTest {

    @Test
    fun `test select task transitions to pending approval`() {
        val slot = SlotContext("issue-1")
        val issue = BacklogIssue(File("test.md"), "issue-1", "Title", 1, "open", emptyList())
        val event = OrchestratorEvent.TaskSelected(issue)

        val transition = SelectTaskState.evaluate(slot, event)

        val next = transition.nextState
        assertTrue(next is PendingApprovalState)
        assertEquals("issue-1", next.issueId)
        assertEquals("Title", next.issueTitle)
        assertTrue(transition.commands.isEmpty())
    }

    @Test
    fun `test pending approval approved generates CreateGitHubIssue`() {
        val slot = SlotContext("issue-1")
        val state = PendingApprovalState("issue-1", "Title", "test.md", githubIssueNumber = null)
        val event = OrchestratorEvent.TelegramApprovalReceived(approved = true)

        val transition = state.evaluate(slot, event)

        assertTrue(transition.nextState is AwaitingJulesStartState)
        val commands = transition.commands
        assertEquals(2, commands.size)
        assertTrue(commands[0] is OrchestratorCommand.PrintLog)
        val createCmd = commands[1] as OrchestratorCommand.CreateGitHubIssue
        assertEquals("issue-1", createCmd.issueId)
        assertEquals("Title", createCmd.title)
    }

    @Test
    fun `test pr build failure event generates CommentOnPr and retains CiRunningState`() {
        val slot = SlotContext("issue-1")
        slot.lastFailedSha = "sha_old"

        val state = CiRunningState("issue-1", "123", "s1", "pr-1")
        val event = OrchestratorEvent.PrBuildStatusFetched(status = "FAILURE", headSha = "sha_new")

        val transition = state.evaluate(slot, event)

        // Assert same state is retained
        assertEquals(state, transition.nextState)

        // Assert exact comment and log commands
        val commands = transition.commands
        assertEquals(3, commands.size)
        assertTrue(commands[0] is OrchestratorCommand.PrintLog)

        val commentCmd = commands[1] as OrchestratorCommand.CommentOnPr
        assertEquals("pr-1", commentCmd.prNumber)
        assertEquals("CI Build Failed", commentCmd.body)

        assertTrue(commands[2] is OrchestratorCommand.SendTelegramNotification)
    }

    @Test
    fun `test tick in select task stays in select task`() {
        val slot = SlotContext("issue-1")
        val transition = SelectTaskState.evaluate(slot, OrchestratorEvent.Tick)
        assertEquals(SelectTaskState, transition.nextState)
        assertTrue(transition.commands.isEmpty())
    }

    @Test
    fun `test pending approval ticks and sends request`() {
        val slot = SlotContext("issue-1")
        slot.approvalRequestSent = false
        val state = PendingApprovalState("issue-1", "Title", "test.md", githubIssueNumber = null)

        val transition = state.evaluate(slot, OrchestratorEvent.Tick)

        assertEquals(state, transition.nextState)
        val commands = transition.commands
        assertEquals(3, commands.size)
        assertTrue(commands[0] is OrchestratorCommand.RingBell)
        assertTrue(commands[1] is OrchestratorCommand.PrintLog)
        assertTrue(commands[2] is OrchestratorCommand.SendApprovalRequest)
    }

    @Test
    fun `test pending approval ticks resumes already approved task`() {
        val slot = SlotContext("issue-1")
        slot.approvalRequestSent = false
        val state = PendingApprovalState("issue-1", "Title", "test.md", githubIssueNumber = "123")

        val transition = state.evaluate(slot, OrchestratorEvent.Tick)

        assertTrue(transition.nextState is AwaitingJulesStartState)
        val commands = transition.commands
        assertEquals(1, commands.size)
        assertTrue(commands[0] is OrchestratorCommand.PrintLog)
    }

    @Test
    fun `test pending approval skipped transitions to select task`() {
        val slot = SlotContext("issue-1")
        val state = PendingApprovalState("issue-1", "Title", "test.md", githubIssueNumber = null)
        val event = OrchestratorEvent.TelegramApprovalReceived(approved = false)

        val transition = state.evaluate(slot, event)

        assertEquals(SelectTaskState, transition.nextState)
        val commands = transition.commands
        assertEquals(1, commands.size)
        assertTrue(commands[0] is OrchestratorCommand.PrintLog)
    }

    @Test
    fun `test pending approval closed transitions to select task`() {
        val slot = SlotContext("issue-1")
        val state = PendingApprovalState("issue-1", "Title", "test.md", githubIssueNumber = "123")
        val event = OrchestratorEvent.IssueClosedDetected(issueNumber = "123")

        val transition = state.evaluate(slot, event)

        assertEquals(SelectTaskState, transition.nextState)
        val commands = transition.commands
        assertEquals(2, commands.size)
        assertTrue(commands[0] is OrchestratorCommand.PrintLog)
        assertTrue(commands[1] is OrchestratorCommand.MarkIssueAsResolved)
    }

    @Test
    fun `test awaiting jules start transitions to select task on closed issue`() {
        val slot = SlotContext("issue-1")
        val state = AwaitingJulesStartState("issue-1", "123")
        val event = OrchestratorEvent.IssueClosedDetected("123")

        val transition = state.evaluate(slot, event)

        assertEquals(SelectTaskState, transition.nextState)
        assertEquals(2, transition.commands.size)
    }

    @Test
    fun `test awaiting jules start transitions to awaiting pr on session detected`() {
        val slot = SlotContext("issue-1")
        val state = AwaitingJulesStartState("issue-1", "123")
        val session = JulesSession("s1", "desc", "repo", "in_progress")
        val event = OrchestratorEvent.JulesSessionDetected(session)

        val transition = state.evaluate(slot, event)

        assertTrue(transition.nextState is AwaitingPrState)
        val commands = transition.commands
        assertEquals(1, commands.size)
        assertTrue(commands[0] is OrchestratorCommand.PrintLog)
    }

    @Test
    fun `test awaiting jules start transitions to ci running on linked pr`() {
        val slot = SlotContext("issue-1")
        val state = AwaitingJulesStartState("issue-1", "123")
        val event = OrchestratorEvent.LinkedPrDetected("pr-1")

        val transition = state.evaluate(slot, event)

        assertTrue(transition.nextState is CiRunningState)
        val commands = transition.commands
        assertEquals(1, commands.size)
    }

    @Test
    fun `test awaiting jules start ticks under limit`() {
        val slot = SlotContext("issue-1")
        slot.julesTriggerAttempts = 5
        val state = AwaitingJulesStartState("issue-1", "123")

        val transition = state.evaluate(slot, OrchestratorEvent.Tick)

        assertEquals(state, transition.nextState)
        assertEquals(2, transition.commands.size)
        assertTrue(transition.commands[0] is OrchestratorCommand.AddLabel)
    }

    @Test
    fun `test awaiting pr transitions to ci running on pr created`() {
        val slot = SlotContext("issue-1")
        val state = AwaitingPrState("issue-1", "123", "s1")
        val event = OrchestratorEvent.PrCreated("pr-1")

        val transition = state.evaluate(slot, event)

        assertTrue(transition.nextState is CiRunningState)
        assertEquals(1, transition.commands.size)
    }

    @Test
    fun `test awaiting pr jules session failed under retry limit`() {
        val slot = SlotContext("issue-1")
        slot.julesRetries = 0
        val state = AwaitingPrState("issue-1", "123", "s1")
        val session = JulesSession("s1", "desc", "repo", "failed")
        val event = OrchestratorEvent.JulesSessionStatusFetched(session, unableToComplete = false)

        val transition = state.evaluate(slot, event)

        assertEquals(state, transition.nextState)
        val commands = transition.commands
        assertEquals(3, commands.size)
        assertTrue(commands[0] is OrchestratorCommand.PrintLog)
        assertTrue(commands[1] is OrchestratorCommand.SendTelegramNotification)
        assertTrue(commands[2] is OrchestratorCommand.SendJulesMessage)
    }

    @Test
    fun `test awaiting pr jules session failed over retry limit`() {
        val slot = SlotContext("issue-1")
        slot.julesRetries = 2
        val state = AwaitingPrState("issue-1", "123", "s1")
        val session = JulesSession("s1", "desc", "repo", "failed")
        val event = OrchestratorEvent.JulesSessionStatusFetched(session, unableToComplete = false)

        val transition = state.evaluate(slot, event)

        assertEquals(SelectTaskState, transition.nextState)
        val commands = transition.commands
        assertEquals(3, commands.size)
        assertTrue(commands[2] is OrchestratorCommand.RemoveGithubIssue)
    }

    @Test
    fun `test ci running transitions to awaiting review on build success`() {
        val slot = SlotContext("issue-1")
        val state = CiRunningState("issue-1", "123", "s1", "pr-1")
        val event = OrchestratorEvent.PrBuildStatusFetched(status = "SUCCESS", headSha = "sha123")

        val transition = state.evaluate(slot, event)

        assertTrue(transition.nextState is AwaitingReviewState)
        assertTrue(transition.commands.isEmpty())
    }

    @Test
    fun `test awaiting review empty commit escalates to human`() {
        val slot = SlotContext("issue-1")
        val state = AwaitingReviewState("issue-1", "123", "s1", "pr-1", "sha123")
        val event = OrchestratorEvent.CommitEmptyChecked(isEmpty = true, newSha = "sha456")

        val transition = state.evaluate(slot, event)

        assertTrue(transition.nextState is AwaitingMergeState)
        assertEquals("sha456", (transition.nextState as AwaitingMergeState).lastHeadSha)
        val commands = transition.commands
        assertEquals(3, commands.size)
        assertTrue(commands[0] is OrchestratorCommand.PrintLog)
        assertTrue(commands[1] is OrchestratorCommand.SendTelegramNotification)
        assertTrue(commands[2] is OrchestratorCommand.RingBell)
    }

    @Test
    fun `test awaiting review non empty commit returns to ci running`() {
        val slot = SlotContext("issue-1")
        val state = AwaitingReviewState("issue-1", "123", "s1", "pr-1", "sha123")
        val event = OrchestratorEvent.CommitEmptyChecked(isEmpty = false, newSha = "sha456")

        val transition = state.evaluate(slot, event)

        assertTrue(transition.nextState is CiRunningState)
        val commands = transition.commands
        assertEquals(1, commands.size)
        assertTrue(commands[0] is OrchestratorCommand.PrintLog)
    }

    @Test
    fun `test awaiting merge ticks and retains`() {
        val slot = SlotContext("issue-1")
        val state = AwaitingMergeState("issue-1", "123", "s1", "pr-1", "sha123")

        val transition = state.evaluate(slot, OrchestratorEvent.Tick)

        assertEquals(state, transition.nextState)
        assertTrue(transition.commands.isEmpty())
    }

    @Test
    fun `test awaiting merge build success retains`() {
        val slot = SlotContext("issue-1")
        val state = AwaitingMergeState("issue-1", "123", "s1", "pr-1", "sha123")
        val event = OrchestratorEvent.PrBuildStatusFetched(status = "SUCCESS", headSha = "sha123")

        val transition = state.evaluate(slot, event)

        assertEquals(state, transition.nextState)
        assertTrue(transition.commands.isEmpty())
    }

    @Test
    fun `test awaiting merge build failure returns to ci running`() {
        val slot = SlotContext("issue-1")
        val state = AwaitingMergeState("issue-1", "123", "s1", "pr-1", "sha123")
        val event = OrchestratorEvent.PrBuildStatusFetched(status = "FAILURE", headSha = "sha123")

        val transition = state.evaluate(slot, event)

        assertTrue(transition.nextState is CiRunningState)
        assertTrue(transition.commands.isEmpty())
    }

    @Test
    fun `test resolve task resolves and picks next`() {
        val slot = SlotContext("issue-1")
        val state = ResolveTaskState("issue-1")

        val transition = state.evaluate(slot, OrchestratorEvent.Tick)

        assertEquals(SelectTaskState, transition.nextState)
        val commands = transition.commands
        assertEquals(5, commands.size)
        assertTrue(commands[0] is OrchestratorCommand.MarkIssueAsResolved)
        assertTrue(commands[1] is OrchestratorCommand.PrintLog)
        assertTrue(commands[2] is OrchestratorCommand.GenerateKnowledgeMap)
        assertTrue(commands[3] is OrchestratorCommand.PrintLog)
        assertTrue(commands[4] is OrchestratorCommand.DeleteStateFile)
    }
}
