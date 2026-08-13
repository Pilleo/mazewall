package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit

sealed class OrchestratorEvent {
    data object Tick : OrchestratorEvent()
    data class TaskSelected(val issue: BacklogIssue) : OrchestratorEvent()
    data object NoTaskSelected : OrchestratorEvent()
    data class TelegramApprovalReceived(val approved: Boolean) : OrchestratorEvent()
    data class IssueClosedDetected(val issueNumber: String) : OrchestratorEvent()
    data class GitHubIssueCreated(val issueNumber: String) : OrchestratorEvent()
    data class LinkedPrDetected(val prNumber: String) : OrchestratorEvent()
    data class JulesSessionDetected(val session: JulesSession) : OrchestratorEvent()
    data object JulesStartTimeout : OrchestratorEvent()
    data class PrCreated(val prNumber: String) : OrchestratorEvent()
    data class JulesSessionStatusFetched(val session: JulesSession, val unableToComplete: Boolean) : OrchestratorEvent()
    data class PrBuildStatusFetched(val status: String, val headSha: String) : OrchestratorEvent()
    data class PrCommentsFetched(val comments: List<GitHubComment>) : OrchestratorEvent()
    data class CommitEmptyChecked(val isEmpty: Boolean, val newSha: String) : OrchestratorEvent()
    data class PrMergedDetected(val prNumber: String) : OrchestratorEvent()
}

sealed class OrchestratorCommand {
    data class PrintLog(val message: String, val isErr: Boolean = false) : OrchestratorCommand()
    data class SendTelegramNotification(val message: String) : OrchestratorCommand()
    data class SendApprovalRequest(val issueId: String, val text: String) : OrchestratorCommand()
    data class RingBell(val times: Int) : OrchestratorCommand()
    data class CreateGitHubIssue(val issueId: String, val title: String, val file: String) : OrchestratorCommand()
    data class WriteGitHubIssueToBacklog(val issueId: String, val issueNumber: Int) : OrchestratorCommand()
    data class AddLabel(val issueNumber: String, val label: String) : OrchestratorCommand()
    data class CommentOnPr(val prNumber: String, val body: String) : OrchestratorCommand()
    data class SendJulesMessage(val sessionId: String, val message: String) : OrchestratorCommand()
    data class MarkIssueAsResolved(val issueId: String) : OrchestratorCommand()
    data class RemoveGithubIssue(val issueId: String) : OrchestratorCommand()
    data object DeleteStateFile : OrchestratorCommand()
    data object GenerateKnowledgeMap : OrchestratorCommand()
    data class ClearPrCache(val prNumber: String) : OrchestratorCommand()
    data class TriggerJulesSession(val issueId: String) : OrchestratorCommand()
}

data class Transition(
    val nextState: OrchestratorState,
    val commands: List<OrchestratorCommand> = emptyList()
)

sealed interface OrchestratorState {
    val name: String
    fun evaluate(slot: SlotContext, event: OrchestratorEvent): Transition {
        return Transition(this)
    }
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
            is CreateGenerationState -> {
                slot.currentIssueId = this.issueId
                slot.githubIssueNumber = this.githubIssueNumber
                slot.julesSessionId = this.julesSessionId
                slot.prNumber = this.prNumber
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
        slot.failedRebaseHeadSha = context.failedRebaseHeadSha
        slot.lastSanitizedRebaseSha = context.lastSanitizedRebaseSha

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
        context.failedRebaseHeadSha = slot.failedRebaseHeadSha
        context.lastSanitizedRebaseSha = slot.lastSanitizedRebaseSha

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
                "CREATE_GENERATION" -> {
                    val issueId = slot.currentIssueId
                    val githubIssueNumber = slot.githubIssueNumber ?: throw IllegalStateException("githubIssueNumber is null in CREATE_GENERATION")
                    val julesSessionId = slot.julesSessionId ?: throw IllegalStateException("julesSessionId is null in CREATE_GENERATION")
                    val prNumber = slot.prNumber ?: throw IllegalStateException("prNumber is null in CREATE_GENERATION")
                    CreateGenerationState(issueId, githubIssueNumber, julesSessionId, prNumber)
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
                "CI_RUNNING" -> {
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
                    val headSha = context.lastHeadSha ?: "dummy-sha"
                    AwaitingReviewState(issueId, githubIssueNumber, julesSessionId, prNumber, headSha)
                }
                "AWAITING_MERGE" -> {
                    val issueId = context.currentIssueId ?: "dummy-issue-id"
                    val githubIssueNumber = context.githubIssueNumber ?: "dummy-github-issue"
                    val julesSessionId = context.julesSessionId ?: "dummy-session-id"
                    val prNumber = context.prNumber ?: "dummy-pr-number"
                    val headSha = context.lastHeadSha ?: "dummy-sha"
                    AwaitingMergeState(issueId, githubIssueNumber, julesSessionId, prNumber, headSha)
                }
                "CREATE_GENERATION" -> {
                    val issueId = context.currentIssueId ?: "dummy-issue-id"
                    val githubIssueNumber = context.githubIssueNumber ?: "dummy-github-issue"
                    val julesSessionId = context.julesSessionId ?: "dummy-session-id"
                    val prNumber = context.prNumber ?: "dummy-pr-number"
                    CreateGenerationState(issueId, githubIssueNumber, julesSessionId, prNumber)
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

    override fun evaluate(slot: SlotContext, event: OrchestratorEvent): Transition {
        return when (event) {
            is OrchestratorEvent.TaskSelected -> {
                val selected = event.issue
                Transition(
                    nextState = PendingApprovalState(
                        issueId = selected.id,
                        issueTitle = selected.title,
                        issueFile = selected.file.path,
                        githubIssueNumber = selected.githubIssue?.toString()
                    )
                )
            }
            else -> Transition(this)
        }
    }

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

        val event = if (selected != null) {
            OrchestratorEvent.TaskSelected(selected)
        } else {
            OrchestratorEvent.NoTaskSelected
        }

        val transition = evaluate(slot, event)
        return transition.nextState
    }
}

data class PendingApprovalState(
    val issueId: String,
    val issueTitle: String,
    val issueFile: String,
    val githubIssueNumber: String? = null
) : OrchestratorState {
    override val name = "PENDING_APPROVAL"

    override fun evaluate(slot: SlotContext, event: OrchestratorEvent): Transition {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return Transition(this)
        }

        return when (event) {
            is OrchestratorEvent.IssueClosedDetected -> {
                Transition(
                    nextState = SelectTaskState,
                    commands = listOf(
                        OrchestratorCommand.PrintLog("\n\u001B[1;33m⚠️ GitHub issue #$githubIssueNumber was closed. Resolving and canceling task $issueId.\u001B[0m"),
                        OrchestratorCommand.MarkIssueAsResolved(issueId)
                    )
                )
            }
            is OrchestratorEvent.TelegramApprovalReceived -> {
                if (!event.approved) {
                    Transition(
                        nextState = SelectTaskState,
                        commands = listOf(
                            OrchestratorCommand.PrintLog("⏭️ Task $issueId skipped by user. Postponing.")
                        )
                    )
                } else {
                    val commands = mutableListOf<OrchestratorCommand>()
                    commands.add(OrchestratorCommand.PrintLog("🚀 Starting task `$issueId`..."))
                    if (githubIssueNumber == null) {
                        commands.add(OrchestratorCommand.CreateGitHubIssue(issueId, issueTitle, issueFile))
                    } else {
                        return Transition(
                            nextState = AwaitingJulesStartState(issueId, githubIssueNumber),
                            commands = commands
                        )
                    }
                    Transition(
                        nextState = AwaitingJulesStartState(issueId, "PENDING"),
                        commands = commands
                    )
                }
            }
            is OrchestratorEvent.Tick -> {
                if (githubIssueNumber != null) {
                    val commands = mutableListOf<OrchestratorCommand>()
                    if (!slot.approvalRequestSent) {
                        commands.add(OrchestratorCommand.PrintLog("🔄 Resuming already-in-progress task $issueId (linked to GitHub issue #$githubIssueNumber)..."))
                    }
                    Transition(
                        nextState = AwaitingJulesStartState(issueId, githubIssueNumber),
                        commands = commands
                    )
                } else {
                    if (!slot.approvalRequestSent) {
                        val commands = mutableListOf<OrchestratorCommand>()
                        commands.add(OrchestratorCommand.RingBell(3))
                        commands.add(OrchestratorCommand.PrintLog("│██? [APPROVAL REQUIRED] Waiting for user approval on Telegram for $issueId... (Press 'Approve' or 'Skip' in Telegram)"))

                        val issueFileObj = File(issueFile)
                        val text = if (issueFileObj.exists()) {
                            val rawBody = issueFileObj.readText().trim()
                            """
                            🤖 *Approval Request: Start Task ${issueId}*

                            $rawBody

                            ----------------------------------
                            Please approve or skip using the inline keyboard below.
                            """.trimIndent()
                        } else {
                            "Start task $issueId - $issueTitle?"
                        }
                        val truncatedText = if (text.length > 4000) text.substring(0, 3997) + "..." else text
                        commands.add(OrchestratorCommand.SendApprovalRequest(issueId, truncatedText))
                        Transition(
                            nextState = this,
                            commands = commands
                        )
                    } else {
                        Transition(this)
                    }
                }
            }
            else -> Transition(this)
        }
    }

    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return this
        }

        val event = if (githubIssueNumber != null) {
            if (env.gitHubClient.isIssueClosed(githubIssueNumber)) {
                OrchestratorEvent.IssueClosedDetected(githubIssueNumber)
            } else {
                if (!slot.approvalRequestSent) {
                    slot.approvalRequestSent = true
                }
                OrchestratorEvent.TelegramApprovalReceived(true)
            }
        } else {
            if (!slot.approvalRequestSent) {
                val t = evaluate(slot, OrchestratorEvent.Tick)
                val interpreter = CommandInterpreter(env, context, slot)
                for (cmd in t.commands) {
                    interpreter.interpret(cmd)
                }
                slot.approvalRequestSent = true
                slot.retryAfterTime = System.currentTimeMillis() + 5000L
                return t.nextState
            } else {
                val approved = env.checkApprovalNonBlocking(issueId)
                if (approved == null) {
                    slot.retryAfterTime = System.currentTimeMillis() + 5000L
                    return this
                }
                OrchestratorEvent.TelegramApprovalReceived(approved)
            }
        }

        val transition = evaluate(slot, event)
        val interpreter = CommandInterpreter(env, context, slot)
        for (cmd in transition.commands) {
            val result = interpreter.interpret(cmd)
            if (cmd is OrchestratorCommand.CreateGitHubIssue && result is String) {
                slot.githubIssueNumber = result
                val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                if (nextIssue != null) {
                    env.writeGithubIssue(nextIssue, result.toInt())
                }
            }
        }

        if (transition.nextState is SelectTaskState) {
            if (event is OrchestratorEvent.TelegramApprovalReceived && !event.approved) {
                context.skippedIds.add(issueId)
            }
            context.activeSlots.remove(slot)
        } else if (transition.nextState is AwaitingJulesStartState) {
            slot.startTime = System.currentTimeMillis()
        }

        return if (transition.nextState is AwaitingJulesStartState) {
            AwaitingJulesStartState(issueId, slot.githubIssueNumber ?: githubIssueNumber ?: "PENDING")
        } else {
            transition.nextState
        }
    }
}

data class AwaitingJulesStartState(
    val issueId: String,
    val githubIssueNumber: String
) : OrchestratorState {
    override val name = "AWAITING_JULES_START"

    override fun evaluate(slot: SlotContext, event: OrchestratorEvent): Transition {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return Transition(this)
        }

        return when (event) {
            is OrchestratorEvent.IssueClosedDetected -> {
                Transition(
                    nextState = SelectTaskState,
                    commands = listOf(
                        OrchestratorCommand.PrintLog("\n\u001B[1;33m⚠️ GitHub issue #$githubIssueNumber was closed. Resolving and canceling task $issueId.\u001B[0m"),
                        OrchestratorCommand.MarkIssueAsResolved(issueId)
                    )
                )
            }
            is OrchestratorEvent.LinkedPrDetected -> {
                Transition(
                    nextState = CiRunningState(issueId, githubIssueNumber, "dummy-session-id", event.prNumber),
                    commands = listOf(
                        OrchestratorCommand.PrintLog("🎉 Found already existing/linked PR #${event.prNumber} for issue #$githubIssueNumber ($issueId). Transitioning straight to CI_RUNNING...")
                    )
                )
            }
            is OrchestratorEvent.JulesSessionDetected -> {
                Transition(
                    nextState = AwaitingPrState(issueId, githubIssueNumber, event.session.id),
                    commands = listOf(
                        OrchestratorCommand.PrintLog("Linked Jules session: ID=${event.session.id}, Status=${event.session.status}")
                    )
                )
            }
            is OrchestratorEvent.Tick -> {
                if (slot.julesTriggerAttempts < 12) {
                    Transition(
                        nextState = this,
                        commands = listOf(
                            OrchestratorCommand.TriggerJulesSession(issueId),
                            OrchestratorCommand.PrintLog("Waiting for Jules session to be automatically triggered via REST API (attempt ${slot.julesTriggerAttempts + 1}/12)...")
                        )
                    )
                } else if (isTaskTimedOut(slot, OrchestratorConfig())) {
                    Transition(
                        nextState = SelectTaskState,
                        commands = listOf(
                            OrchestratorCommand.PrintLog("[ERROR] Task $issueId timed out waiting for Jules session. Returning to SELECT_TASK.", isErr = true)
                        )
                    )
                } else {
                    Transition(
                        nextState = this,
                        commands = listOf(
                            OrchestratorCommand.PrintLog("⚠️ Jules session did not trigger. Retrying in 1 minute...")
                        )
                    )
                }
            }
            else -> Transition(this)
        }
    }

    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return this
        }

        val event = if (env.gitHubClient.isIssueClosed(githubIssueNumber)) {
            OrchestratorEvent.IssueClosedDetected(githubIssueNumber)
        } else {
            val existingPr = env.gitHubClient.findLinkedPR(githubIssueNumber, issueId, null)
            if (existingPr != null) {
                OrchestratorEvent.LinkedPrDetected(existingPr)
            } else {
                val activeSession = env.julesClient.getActiveSession(issueId)
                if (activeSession != null) {
                    OrchestratorEvent.JulesSessionDetected(activeSession)
                } else {
                    OrchestratorEvent.Tick
                }
            }
        }

        val transition = evaluate(slot, event)
        val interpreter = CommandInterpreter(env, context, slot)

        if (event is OrchestratorEvent.Tick) {
            if (slot.julesTriggerAttempts < env.config.julesTriggerAttempts) {
                slot.julesTriggerAttempts++
                for (cmd in transition.commands) {
                    val result = interpreter.interpret(cmd)
                    if (cmd is OrchestratorCommand.TriggerJulesSession && result is JulesSession) {
                        slot.julesSessionId = result.id
                        return AwaitingPrState(issueId, githubIssueNumber, result.id)
                    }
                }
                slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.julesTriggerIntervalSeconds)
                return this
            } else if (isTaskTimedOut(slot, env.config)) {
                env.errPrintln("❌ Task $issueId timed out waiting for Jules session. Returning to SELECT_TASK.")
                context.activeSlots.remove(slot)
                return SelectTaskState
            } else {
                env.println("⚠️ Jules session did not trigger. Retrying in 1 minute...")
                slot.julesTriggerAttempts = 0
                slot.retryAfterTime = currentTime + TimeUnit.MINUTES.toMillis(1)
                return this
            }
        }

        for (cmd in transition.commands) {
            interpreter.interpret(cmd)
        }

        if (transition.nextState is SelectTaskState) {
            context.activeSlots.remove(slot)
        } else if (transition.nextState is CiRunningState) {
            val existingPr = env.gitHubClient.findLinkedPR(githubIssueNumber, issueId, null)
            val activeSessionId = env.julesClient.getActiveSession(issueId)?.id ?: "dummy-session-id"
            slot.prNumber = existingPr
            slot.julesSessionId = activeSessionId
            slot.retryAfterTime = 0L
            slot.julesTriggerAttempts = 0
            return CiRunningState(issueId, githubIssueNumber, activeSessionId, existingPr ?: "dummy-pr")
        } else if (transition.nextState is AwaitingPrState) {
            val activeSession = env.julesClient.getActiveSession(issueId)
            slot.retryAfterTime = 0L
            slot.julesTriggerAttempts = 0
            return AwaitingPrState(issueId, githubIssueNumber, activeSession?.id ?: "dummy-session")
        }

        return transition.nextState
    }
}

data class AwaitingPrState(
    val issueId: String,
    val githubIssueNumber: String,
    val julesSessionId: String
) : OrchestratorState {
    override val name = "AWAITING_PR"

    override fun evaluate(slot: SlotContext, event: OrchestratorEvent): Transition {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return Transition(this)
        }

        return when (event) {
            is OrchestratorEvent.IssueClosedDetected -> {
                Transition(
                    nextState = SelectTaskState,
                    commands = listOf(
                        OrchestratorCommand.PrintLog("\n\u001B[1;33m⚠️ GitHub issue #$githubIssueNumber was closed. Resolving and canceling task $issueId.\u001B[0m"),
                        OrchestratorCommand.MarkIssueAsResolved(issueId)
                    )
                )
            }
            is OrchestratorEvent.PrCreated -> {
                Transition(
                    nextState = CiRunningState(issueId, githubIssueNumber, julesSessionId, event.prNumber),
                    commands = listOf(
                        OrchestratorCommand.PrintLog("🎉 Jules opened PR #${event.prNumber}")
                    )
                )
            }
            is OrchestratorEvent.JulesSessionStatusFetched -> {
                val session = event.session
                val status = session.status.lowercase()
                val isFailed = event.unableToComplete || status == "failed" || status == "cancelled"

                if (isFailed) {
                    if (slot.julesRetries < 2) {
                        Transition(
                            nextState = this,
                            commands = listOf(
                                OrchestratorCommand.PrintLog("\n⚠️ [RETRY] Jules task $issueId failed with status: ${session.status}. Retrying (Attempt ${slot.julesRetries + 1}/2)..."),
                                OrchestratorCommand.SendTelegramNotification("⚠️ *Jules task failed* for $issueId (Status: ${session.status}). Sending 'Retry' message to Jules."),
                                OrchestratorCommand.SendJulesMessage(session.id, "Retry")
                            )
                        )
                    } else {
                        Transition(
                            nextState = SelectTaskState,
                            commands = listOf(
                                OrchestratorCommand.PrintLog("\n❌ [FAILED] Jules task $issueId failed after ${slot.julesRetries} retries."),
                                OrchestratorCommand.SendTelegramNotification("❌ *Jules task failed* for $issueId after ${slot.julesRetries} retries. Returning to SELECT_TASK."),
                                OrchestratorCommand.RemoveGithubIssue(issueId)
                            )
                        )
                    }
                } else {
                    val commands = mutableListOf<OrchestratorCommand>()
                    if (session.status != slot.lastBuildStatus) {
                        commands.add(OrchestratorCommand.PrintLog("Jules session status changed: ${session.status}"))

                        if (session.status.contains("Awaiting", ignoreCase = true) || session.status.contains("Feedback", ignoreCase = true)) {
                            val sessionUrl = "https://jules.google.com/session/${session.id.substringAfterLast("/")}"
                            commands.add(OrchestratorCommand.SendTelegramNotification("⚠️ *Jules needs feedback on task $issueId!* Status: `${session.status}`. Please check and respond here: $sessionUrl"))
                            commands.add(OrchestratorCommand.PrintLog("\n\u001B[1;31m🔔 [FEEDBACK REQUIRED] Jules is blocked waiting for feedback on task $issueId. Status: ${session.status}\u001B[0m"))
                            commands.add(OrchestratorCommand.PrintLog("👉 Respond here: $sessionUrl"))
                            commands.add(OrchestratorCommand.RingBell(5))
                        } else if (session.status.equals("Completed", ignoreCase = true)) {
                            val isReviewTask = issueId.contains("review-task")
                            if (isReviewTask) {
                                val sessionUrl = "https://jules.google.com/session/${session.id.substringAfterLast("/")}"
                                return Transition(
                                    nextState = SelectTaskState,
                                    commands = listOf(
                                        OrchestratorCommand.PrintLog("\n\u001B[1;32m🟢 [REVIEW TASK COMPLETED] Review task $issueId is Completed!\u001B[0m"),
                                        OrchestratorCommand.SendTelegramNotification("🟢 *Review Task Completed!* `$issueId` (GitHub Issue #$githubIssueNumber)\n👉 Check results on GitHub issue #$githubIssueNumber or Jules UI: $sessionUrl"),
                                        OrchestratorCommand.MarkIssueAsResolved(issueId)
                                    )
                                )
                            } else {
                                val now = System.currentTimeMillis()
                                if (now - slot.lastWaitingLogTime > 600_000) {
                                    val sessionUrl = "https://jules.google.com/session/${session.id.substringAfterLast("/")}"
                                    commands.add(OrchestratorCommand.PrintLog("\n\u001B[1;32m🟢 [COMPLETED] Jules task $issueId is Completed! Please review and publish the PR in the UI.\u001B[0m"))
                                    commands.add(OrchestratorCommand.PrintLog("👉 Publish PR here: $sessionUrl"))
                                }
                            }
                        }
                    }
                    Transition(this, commands)
                }
            }
            else -> Transition(this)
        }
    }

    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return this
        }

        val event = if (env.gitHubClient.isIssueClosed(githubIssueNumber)) {
            OrchestratorEvent.IssueClosedDetected(githubIssueNumber)
        } else {
            val prNumber = slot.prNumber ?: env.gitHubClient.findLinkedPR(githubIssueNumber, issueId, julesSessionId)
            if (prNumber != null) {
                OrchestratorEvent.PrCreated(prNumber)
            } else {
                val session = env.julesClient.getActiveSession(issueId)
                if (session != null) {
                    val unable = env.julesClient.hasUnableToCompleteActivity(session.id)
                    OrchestratorEvent.JulesSessionStatusFetched(session, unable)
                } else {
                    OrchestratorEvent.Tick
                }
            }
        }

        val transition = evaluate(slot, event)
        val interpreter = CommandInterpreter(env, context, slot)
        for (cmd in transition.commands) {
            interpreter.interpret(cmd)
        }

        if (event is OrchestratorEvent.PrCreated) {
            slot.prNumber = event.prNumber
            slot.lastBuildStatus = null
            slot.lastHeadSha = null
            slot.lastCheckedSha = null
            slot.julesRetries = 0
            return transition.nextState
        }

        if (event is OrchestratorEvent.JulesSessionStatusFetched) {
            val session = event.session
            val status = session.status.lowercase()
            val isFailed = event.unableToComplete || status == "failed" || status == "cancelled"
            if (isFailed) {
                if (slot.julesRetries < 2) {
                    slot.julesRetries++
                    slot.lastBuildStatus = null
                    return this
                } else {
                    val nextIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                    if (nextIssue != null) {
                        env.removeGithubIssue(nextIssue)
                    }
                    context.skippedIds.add(issueId)
                    context.activeSlots.remove(slot)
                    return SelectTaskState
                }
            } else {
                if (session.status != slot.lastBuildStatus) {
                    slot.lastBuildStatus = session.status
                    if (session.status.equals("Completed", ignoreCase = true)) {
                        val isReviewTask = issueId.contains("review-task")
                        if (isReviewTask) {
                            context.activeSlots.remove(slot)
                            return SelectTaskState
                        }
                    }
                }
            }
        }

        if (transition.nextState is SelectTaskState) {
            context.skippedIds.add(issueId)
            context.activeSlots.remove(slot)
        }

        if (isTaskTimedOut(slot, env.config)) {
            env.errPrintln("❌ Task $issueId timed out waiting for PR creation. Deferring task and returning to SELECT_TASK.")
            val timedOutIssue = env.parseAllIssues().firstOrNull { it.id == issueId }
                ?: error("Cannot defer timed-out task $issueId: backlog issue not found")
            env.markIssueAsDeferred(timedOutIssue)
            context.skippedIds.add(issueId)
            context.activeSlots.remove(slot)
            return SelectTaskState
        }

        val now = System.currentTimeMillis()
        if (now - slot.lastWaitingLogTime > 600_000) {
            env.println("⌛ Waiting for Jules PR to be published for task $issueId...")
            slot.lastWaitingLogTime = now
        }

        return transition.nextState
    }
}

data class CiRunningState(
    val issueId: String,
    val githubIssueNumber: String,
    val julesSessionId: String,
    val prNumber: String
) : OrchestratorState {
    override val name = "CI_RUNNING"

    override fun evaluate(slot: SlotContext, event: OrchestratorEvent): Transition {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return Transition(this)
        }

        return when (event) {
            is OrchestratorEvent.PrMergedDetected -> {
                Transition(
                    nextState = ResolveTaskState(issueId),
                    commands = listOf(
                        OrchestratorCommand.PrintLog("🎉 PR #$prNumber merged! resolving issue locally...")
                    )
                )
            }
            is OrchestratorEvent.IssueClosedDetected -> {
                Transition(
                    nextState = SelectTaskState,
                    commands = listOf(
                        OrchestratorCommand.PrintLog("\n\u001B[1;33m⚠️ GitHub issue #$githubIssueNumber was closed. Resolving and canceling task $issueId.\u001B[0m"),
                        OrchestratorCommand.MarkIssueAsResolved(issueId)
                    )
                )
            }
            is OrchestratorEvent.JulesSessionStatusFetched -> {
                val session = event.session
                val isFailed = event.unableToComplete || session.status.lowercase() == "failed" || session.status.lowercase() == "cancelled"
                if (isFailed) {
                    val statText = session.status
                    if (slot.julesRetries < 2) {
                        Transition(
                            nextState = this,
                            commands = listOf(
                                OrchestratorCommand.PrintLog("\n⚠️ [RETRY] Jules session failed during CI: $statText (or has unable to complete activity). Retrying (Attempt ${slot.julesRetries + 1}/2)..."),
                                OrchestratorCommand.SendTelegramNotification("⚠️ *Jules session failed* during CI for $issueId (Status: $statText). Sending 'Retry' message to Jules (Attempt ${slot.julesRetries + 1}/2)."),
                                OrchestratorCommand.SendJulesMessage(session.id, "Retry")
                            )
                        )
                    } else {
                        Transition(
                            nextState = SelectTaskState,
                            commands = listOf(
                                OrchestratorCommand.PrintLog("\n❌ [FAILED] Jules session failed during CI: $statText after ${slot.julesRetries} retries."),
                                OrchestratorCommand.SendTelegramNotification("❌ *Jules session failed* during CI for $issueId after ${slot.julesRetries} retries. Returning to SELECT_TASK."),
                                OrchestratorCommand.RemoveGithubIssue(issueId)
                            )
                        )
                    }
                } else if (session.status.lowercase() == "in_progress") {
                    Transition(
                        nextState = this,
                        commands = listOf(
                            OrchestratorCommand.PrintLog("Jules session ${session.id} is actively running (IN_PROGRESS). Waiting...")
                        )
                    )
                } else {
                    Transition(this)
                }
            }
            is OrchestratorEvent.PrBuildStatusFetched -> {
                val status = event.status
                val currentSha = event.headSha
                when (status) {
                    "SUCCESS" -> {
                        Transition(AwaitingReviewState(issueId, githubIssueNumber, julesSessionId, prNumber, currentSha))
                    }
                    "FAILURE" -> {
                        val commands = mutableListOf<OrchestratorCommand>()
                        if (currentSha != slot.lastFailedSha) {
                            commands.add(OrchestratorCommand.PrintLog("❌ Build failed on PR #$prNumber. Fetching logs..."))
                            commands.add(OrchestratorCommand.CommentOnPr(prNumber, "CI Build Failed"))
                            commands.add(OrchestratorCommand.SendTelegramNotification("❌ Build failed on PR #$prNumber. Feedback sent to Jules."))
                        } else {
                            commands.add(OrchestratorCommand.PrintLog("❌ Build is still failing on SHA $currentSha. Waiting for a new commit..."))
                        }
                        Transition(this, commands)
                    }
                    "CONFLICT" -> {
                        Transition(this)
                    }
                    else -> {
                        val commands = mutableListOf<OrchestratorCommand>()
                        val now = System.currentTimeMillis()
                        if (status != slot.lastKnownStatus) {
                            // status changed
                        } else if (now - slot.lastStatusChangeTime > 900_000 && slot.lastPendingNotificationTime == 0L) {
                            val msg = "⚠️ *PR #$prNumber build status is stuck in $status!* Please check the runner: mock url"
                            commands.add(OrchestratorCommand.PrintLog("\u001B[1;31m🔔 [STUCK] PR #$prNumber build status is stuck in $status! Please check the runner: mock url\u001B[0m"))
                            commands.add(OrchestratorCommand.SendTelegramNotification(msg))
                            commands.add(OrchestratorCommand.RingBell(1))
                        }
                        Transition(this, commands)
                    }
                }
            }
            else -> Transition(this)
        }
    }

    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return this
        }

        if (env.gitHubClient.isPrMerged(prNumber)) {
            val transition = evaluate(slot, OrchestratorEvent.PrMergedDetected(prNumber))
            CommandInterpreter(env, context, slot).interpret(transition.commands.first())
            return transition.nextState
        }

        if (env.gitHubClient.isIssueClosed(githubIssueNumber)) {
            val transition = evaluate(slot, OrchestratorEvent.IssueClosedDetected(githubIssueNumber))
            val interpreter = CommandInterpreter(env, context, slot)
            for (cmd in transition.commands) interpreter.interpret(cmd)
            context.skippedIds.add(issueId)
            context.activeSlots.remove(slot)
            return SelectTaskState
        }

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
            slot.lastHeadSha = currentSha
            slot.lastKnownStatus = null
            slot.lastStatusChangeTime = 0L
            slot.lastPendingNotificationTime = 0L
            slot.lastRequestedReviewSha = null
        }

        val session = env.julesClient.getActiveSession(issueId)
        val isFailed = if (session != null) {
            val stat = session.status.lowercase()
            stat == "failed" || stat == "cancelled" || env.julesClient.hasUnableToCompleteActivity(session.id)
        } else {
            env.julesClient.hasUnableToCompleteActivity(julesSessionId)
        }

        if (isFailed) {
            val transition = evaluate(slot, OrchestratorEvent.JulesSessionStatusFetched(session ?: JulesSession(julesSessionId, "", "", "FAILED"), isFailed))
            val interpreter = CommandInterpreter(env, context, slot)
            for (cmd in transition.commands) interpreter.interpret(cmd)

            if (slot.julesRetries < 2) {
                slot.julesRetries++
                slot.lastBuildStatus = null
                slot.lastHeadSha = null
                slot.julesSessionFailureWaitAttempts = 1
                slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(20)
                return this
            } else {
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
            val transition = evaluate(slot, OrchestratorEvent.JulesSessionStatusFetched(session, false))
            val interpreter = CommandInterpreter(env, context, slot)
            for (cmd in transition.commands) interpreter.interpret(cmd)
            slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
            return this
        }

        val outcome = handleRebaseAndConflicts(env, slot, prNumber, issueId, githubIssueNumber, julesSessionId)
        when (outcome) {
            RebaseOutcome.WAITING_RETRY -> {
                if (slot.retryAfterTime <= currentTime) {
                    slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
                }
                return this
            }
            RebaseOutcome.CREATE_NEW_GENERATION -> return CreateGenerationState(issueId, githubIssueNumber, julesSessionId, prNumber)
            RebaseOutcome.ALREADY_SANITIZED -> { /* proceed */ }
        }

        if (env.gitHubClient.isPrMerged(prNumber)) {
            return ResolveTaskState(issueId)
        }

        val status = env.gitHubClient.checkBuildStatus(prNumber)
        if (status != slot.lastBuildStatus || currentSha != slot.lastCheckedSha) {
            env.println("PR #$prNumber build check: $status")
            slot.lastBuildStatus = status
            slot.lastCheckedSha = currentSha
        }

        val transition = evaluate(slot, OrchestratorEvent.PrBuildStatusFetched(status, currentSha))
        val interpreter = CommandInterpreter(env, context, slot)

        if (status == "FAILURE") {
            if (currentSha != slot.lastFailedSha) {
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
                slot.lastFailedSha = currentSha
            } else {
                env.println("❌ Build is still failing on SHA $currentSha. Waiting for a new commit...")
            }
            slot.retryAfterTime = currentTime + TimeUnit.MINUTES.toMillis(env.config.ciFailureRetryMinutes)
            return this
        }

        for (cmd in transition.commands) {
            interpreter.interpret(cmd)
        }

        if (status == "SUCCESS") {
            return transition.nextState
        } else if (status == "CONFLICT") {
            slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
            return this
        } else {
            val now = System.currentTimeMillis()
            if (status != slot.lastKnownStatus) {
                slot.lastKnownStatus = status
                slot.lastStatusChangeTime = now
                slot.lastPendingNotificationTime = 0L
            } else if (now - slot.lastStatusChangeTime > env.config.stuckPendingThresholdMs && slot.lastPendingNotificationTime == 0L) {
                slot.lastPendingNotificationTime = now
            }
            slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
            return this
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

    override fun evaluate(slot: SlotContext, event: OrchestratorEvent): Transition {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return Transition(this)
        }

        return when (event) {
            is OrchestratorEvent.IssueClosedDetected -> {
                Transition(
                    nextState = SelectTaskState,
                    commands = listOf(
                        OrchestratorCommand.PrintLog("\n\u001B[1;33m⚠️ GitHub issue #$githubIssueNumber was closed. Resolving and canceling task $issueId.\u001B[0m"),
                        OrchestratorCommand.MarkIssueAsResolved(issueId)
                    )
                )
            }
            is OrchestratorEvent.PrMergedDetected -> {
                Transition(
                    nextState = ResolveTaskState(issueId),
                    commands = listOf(
                        OrchestratorCommand.PrintLog("🎉 PR #$prNumber merged! resolving issue locally...")
                    )
                )
            }
            is OrchestratorEvent.JulesSessionStatusFetched -> {
                val session = event.session
                val isFailed = event.unableToComplete || session.status.lowercase() == "failed" || session.status.lowercase() == "cancelled"
                if (isFailed) {
                    val statText = session.status
                    Transition(
                        nextState = this,
                        commands = listOf(
                            OrchestratorCommand.PrintLog("\n⚠️ [RETRY] Jules session failed during review: $statText (or has unable to complete activity). Retrying..."),
                            OrchestratorCommand.SendTelegramNotification("⚠️ *Jules session failed* during review on PR #$prNumber. Sending 'Retry' message to Jules."),
                            OrchestratorCommand.SendJulesMessage(session.id, "Retry")
                        )
                    )
                } else if (session.status.lowercase() == "in_progress") {
                    Transition(
                        nextState = this,
                        commands = listOf(
                            OrchestratorCommand.PrintLog("Jules session ${session.id} is actively running (IN_PROGRESS) in AWAITING_REVIEW. Waiting...")
                        )
                    )
                } else {
                    Transition(this)
                }
            }
            is OrchestratorEvent.CommitEmptyChecked -> {
                if (event.isEmpty) {
                    Transition(
                        nextState = AwaitingMergeState(issueId, githubIssueNumber, julesSessionId, prNumber, event.newSha),
                        commands = listOf(
                            OrchestratorCommand.PrintLog("⚠️ Jules pushed an empty commit during review phase on PR #$prNumber"),
                            OrchestratorCommand.SendTelegramNotification("⚠️ Jules pushed an empty commit during review phase on PR #$prNumber"),
                            OrchestratorCommand.RingBell(5)
                        )
                    )
                } else {
                    Transition(
                        nextState = CiRunningState(issueId, githubIssueNumber, julesSessionId, prNumber),
                        commands = listOf(
                            OrchestratorCommand.PrintLog("🟢 Jules pushed a non-empty commit on PR #$prNumber. Treating as real problem resolution. Resetting push count and returning to CI_RUNNING.")
                        )
                    )
                }
            }
            is OrchestratorEvent.PrBuildStatusFetched -> {
                if (event.status != "SUCCESS") {
                    Transition(CiRunningState(issueId, githubIssueNumber, julesSessionId, prNumber))
                } else {
                    Transition(this)
                }
            }
            is OrchestratorEvent.PrCommentsFetched -> {
                val comments = event.comments
                if (slot.julesReviewAttemptCount >= 3) {
                    Transition(
                        nextState = AwaitingMergeState(issueId, githubIssueNumber, julesSessionId, prNumber, lastHeadSha),
                        commands = listOf(
                            OrchestratorCommand.PrintLog("⚠️ PR #$prNumber Build Passed, but Jules review attempt count exceeded limit (3). Bypassing review."),
                            OrchestratorCommand.SendTelegramNotification("⚠️ PR #$prNumber: Bypassing Jules review (attempt count exceeded limit).")
                        )
                    )
                } else {
                    val searchSha = slot.lastRequestedReviewSha ?: lastHeadSha
                    val shaPrefix = searchSha.take(7)
                    val requestComment = comments.firstOrNull {
                        it.body.contains("@jules") && it.body.contains(shaPrefix)
                    }

                    if (requestComment == null) {
                        val pushWarning = if (slot.julesReviewPushCount > 0) {
                            "\n\n🚨 **IMPORTANT — PREVIOUS ATTEMPT PUSHED CODE**: Your previous review attempt " +
                            "resulted in a commit push instead of a comment. This is incorrect. " +
                            "You must NOT push anything. Read the instructions below carefully before acting."
                        } else ""
                        val prompt = OrchestratorPrompts.reviewPrompt(prNumber, shaPrefix, pushWarning)
                        Transition(
                            nextState = this,
                            commands = listOf(
                                OrchestratorCommand.PrintLog("🤖 PR #$prNumber Build Passed. Requesting Jules review for SHA: $lastHeadSha (Attempt ${slot.julesReviewAttemptCount + 1}/3)"),
                                OrchestratorCommand.CommentOnPr(prNumber, prompt)
                            )
                        )
                    } else {
                        val requestTime = java.time.Instant.parse(requestComment.createdAt)
                        val julesReply = comments.firstOrNull { comment ->
                            val author = comment.author?.login ?: ""
                            author.contains("jules", ignoreCase = true) && java.time.Instant.parse(comment.createdAt).isAfter(requestTime)
                        }

                        if (julesReply != null) {
                            val verdict = when {
                                julesReply.body.contains("VERDICT: APPROVED") -> "✅ APPROVED"
                                julesReply.body.contains("VERDICT: NEEDS_CHANGES") -> "🔶 NEEDS_CHANGES"
                                julesReply.body.contains("VERDICT: UNCERTAIN") -> "❓ UNCERTAIN"
                                else -> "⚠️ NO_VERDICT (Jules did not include a structured verdict)"
                            }
                            Transition(
                                nextState = AwaitingMergeState(issueId, githubIssueNumber, julesSessionId, prNumber, lastHeadSha),
                                commands = listOf(
                                    OrchestratorCommand.PrintLog("🟢 Jules review received for SHA $lastHeadSha."),
                                    OrchestratorCommand.PrintLog("Jules verdict on PR #$prNumber: $verdict"),
                                    OrchestratorCommand.SendTelegramNotification("🟢 *Jules reviewed PR #$prNumber!* Verdict: $verdict\nReady for merge: mock url"),
                                    OrchestratorCommand.RingBell(3)
                                )
                            )
                        } else {
                            Transition(
                                nextState = this,
                                commands = listOf(
                                    OrchestratorCommand.PrintLog("⌛ Waiting for Jules (@jules) to complete review on PR #$prNumber (SHA: $shaPrefix)...")
                                )
                            )
                        }
                    }
                }
            }
            else -> Transition(this)
        }
    }

    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return this
        }

        if (env.gitHubClient.isIssueClosed(githubIssueNumber)) {
            val transition = evaluate(slot, OrchestratorEvent.IssueClosedDetected(githubIssueNumber))
            val interpreter = CommandInterpreter(env, context, slot)
            for (cmd in transition.commands) interpreter.interpret(cmd)
            context.skippedIds.add(issueId)
            context.activeSlots.remove(slot)
            return SelectTaskState
        }

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
            val transition = evaluate(slot, OrchestratorEvent.PrMergedDetected(prNumber))
            CommandInterpreter(env, context, slot).interpret(transition.commands.first())
            return transition.nextState
        }

        val currentSha = env.gitHubClient.getPrHeadSha(prNumber)
        if (currentSha != slot.lastHeadSha) {
            env.gitHubClient.clearPrCache(prNumber)
        }

        val session = env.julesClient.getActiveSession(issueId)
        val isFailed = if (session != null) {
            val stat = session.status.lowercase()
            stat == "failed" || stat == "cancelled" || env.julesClient.hasUnableToCompleteActivity(session.id)
        } else {
            env.julesClient.hasUnableToCompleteActivity(julesSessionId)
        }

        if (isFailed) {
            val transition = evaluate(slot, OrchestratorEvent.JulesSessionStatusFetched(session ?: JulesSession(julesSessionId, "", "", "FAILED"), isFailed))
            val interpreter = CommandInterpreter(env, context, slot)
            for (cmd in transition.commands) interpreter.interpret(cmd)
            slot.lastReviewedSha = null
            slot.julesSessionFailureWaitAttempts = 1
            slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(20)
            return this
        }

        if (session != null && session.status.lowercase() == "in_progress") {
            val transition = evaluate(slot, OrchestratorEvent.JulesSessionStatusFetched(session, false))
            val interpreter = CommandInterpreter(env, context, slot)
            for (cmd in transition.commands) interpreter.interpret(cmd)
            slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
            return this
        }

        val outcome = handleRebaseAndConflicts(env, slot, prNumber, issueId, githubIssueNumber, julesSessionId)
        when (outcome) {
            RebaseOutcome.WAITING_RETRY -> {
                if (slot.retryAfterTime <= currentTime) {
                    slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
                }
                return this
            }
            RebaseOutcome.CREATE_NEW_GENERATION -> return CreateGenerationState(issueId, githubIssueNumber, julesSessionId, prNumber)
            RebaseOutcome.ALREADY_SANITIZED -> { /* proceed */ }
        }

        val buildStatus = env.gitHubClient.checkBuildStatus(prNumber)

        if (currentSha != slot.lastHeadSha) {
            val shaOld = slot.lastHeadSha ?: ""
            val isEmpty = if (shaOld.isNotEmpty()) {
                env.gitHubClient.isCommitEmpty(prNumber, shaOld, currentSha)
            } else {
                false
            }

            slot.lastHeadSha = currentSha
            slot.lastRequestedReviewSha = null

            val transition = evaluate(slot, OrchestratorEvent.CommitEmptyChecked(isEmpty, currentSha))
            val interpreter = CommandInterpreter(env, context, slot)
            for (cmd in transition.commands) interpreter.interpret(cmd)

            if (isEmpty) {
                return transition.nextState
            } else {
                slot.julesReviewPushCount = 0
                return CiRunningState(issueId, githubIssueNumber, julesSessionId, prNumber)
            }
        }

        if (buildStatus != "SUCCESS") {
            return CiRunningState(issueId, githubIssueNumber, julesSessionId, prNumber)
        }

        if (currentSha != slot.lastReviewedSha) {
            if (slot.julesReviewAttemptCount >= 3) {
                val transition = evaluate(slot, OrchestratorEvent.PrCommentsFetched(emptyList()))
                val interpreter = CommandInterpreter(env, context, slot)
                for (cmd in transition.commands) interpreter.interpret(cmd)
                slot.lastReviewedSha = currentSha
                return transition.nextState
            }

            val comments = env.gitHubClient.getPrComments(prNumber)
            val searchSha = slot.lastRequestedReviewSha ?: currentSha
            val shaPrefix = searchSha.take(7)

            val requestComment = comments.firstOrNull {
                (it.body.contains("@jules")) &&
                it.body.contains(shaPrefix)
            }

            if (requestComment == null) {
                val transition = evaluate(slot, OrchestratorEvent.PrCommentsFetched(comments))
                val interpreter = CommandInterpreter(env, context, slot)
                for (cmd in transition.commands) {
                    interpreter.interpret(cmd)
                }
                slot.julesReviewAttemptCount++
                slot.lastRequestedReviewSha = currentSha
                slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
                return this
            } else {
                val transition = evaluate(slot, OrchestratorEvent.PrCommentsFetched(comments))
                val interpreter = CommandInterpreter(env, context, slot)
                for (cmd in transition.commands) {
                    interpreter.interpret(cmd)
                }

                val requestTime = java.time.Instant.parse(requestComment.createdAt)
                val julesReply = comments.firstOrNull { comment ->
                    val author = comment.author?.login ?: ""
                    (author.contains("jules", ignoreCase = true)) &&
                    java.time.Instant.parse(comment.createdAt).isAfter(requestTime)
                }

                if (julesReply != null) {
                    slot.lastReviewedSha = currentSha
                    return transition.nextState
                } else {
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

    override fun evaluate(slot: SlotContext, event: OrchestratorEvent): Transition {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return Transition(this)
        }

        return when (event) {
            is OrchestratorEvent.PrMergedDetected -> {
                Transition(
                    nextState = ResolveTaskState(issueId),
                    commands = listOf(
                        OrchestratorCommand.PrintLog("🎉 PR #$prNumber merged! resolving issue locally...")
                    )
                )
            }
            is OrchestratorEvent.PrBuildStatusFetched -> {
                if (event.headSha != lastHeadSha || event.status != "SUCCESS") {
                    Transition(CiRunningState(issueId, githubIssueNumber, julesSessionId, prNumber))
                } else {
                    Transition(this)
                }
            }
            else -> Transition(this)
        }
    }

    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return this
        }

        if (env.gitHubClient.isPrMerged(prNumber)) {
            val transition = evaluate(slot, OrchestratorEvent.PrMergedDetected(prNumber))
            CommandInterpreter(env, context, slot).interpret(transition.commands.first())
            return transition.nextState
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

data class CreateGenerationState(
    val issueId: String,
    val githubIssueNumber: String,
    val julesSessionId: String,
    val prNumber: String
) : OrchestratorState {
    override val name = "CREATE_GENERATION"

    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val currentTime = System.currentTimeMillis()
        if (currentTime < slot.retryAfterTime) {
            return this
        }

        env.println("🚀 Starting new generation for task $issueId (Gen ${slot.generation + 1})...")

        // 1. Mark current PR as superseded. Repositories do not necessarily pre-provision this label.
        try {
            env.gitHubClient.ensureLabelExists("superseded")
            env.gitHubClient.labelPr(prNumber, "superseded")
        } catch (e: Exception) {
            env.errPrintln("❌ Failed to mark PR #$prNumber as superseded: ${e.message}")
            slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
            return this
        }

        // 2. Extract info
        val prUrl = env.gitHubClient.getPrUrl(prNumber)
        val branch = env.gitHubClient.getPrHeadSha(prNumber) // or ref name, but sha is fine, we can use pr details
        val previousBranch = env.gitHubClient.getPrHeadSha(prNumber)

        val issue = env.parseAllIssues().firstOrNull { it.id == issueId }
        val issueDescription = issue?.file?.readText() ?: "Original description unavailable."

        // 3. Create new Jules session
        val newSession = try {
            env.julesClient.createSessionWithContext(
                repo = env.gitHubClient.getRepoName(),
                issueId = issueId,
                githubIssueNumber = githubIssueNumber,
                previousPrUrl = prUrl,
                previousBranch = "PR #$prNumber (SHA: $previousBranch)",
                originalTaskDescription = issueDescription
            )
        } catch (e: Exception) {
            env.errPrintln("❌ Failed to create generation session via Jules API: ${e.message}")
            slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
            return this
        }

        // 4. Comment on the issue
        env.gitHubClient.commentOnIssue(
            githubIssueNumber,
            "🚨 Merge conflict detected on PR #$prNumber. Started new Jules session (Generation ${slot.generation + 1}) to resolve conflicts against master: https://jules.google.com/session/${newSession.id}"
        )

        // 5. Update slot and transition
        slot.generation++
        slot.previousPrNumber = prNumber
        slot.julesSessionId = newSession.id
        slot.prNumber = null
        slot.lastHeadSha = null
        slot.lastReviewedSha = null
        slot.lastRequestedReviewSha = null
        slot.lastBuildStatus = null
        slot.lastCheckedSha = null
        slot.julesRetries = 0
        slot.julesReviewPushCount = 0
        slot.julesReviewAttemptCount = 0

        return AwaitingPrState(issueId, githubIssueNumber, newSession.id)
    }
}

data class ResolveTaskState(
    val issueId: String
) : OrchestratorState {
    override val name = "RESOLVE_TASK"

    override fun evaluate(slot: SlotContext, event: OrchestratorEvent): Transition {
        return Transition(
            nextState = SelectTaskState,
            commands = listOf(
                OrchestratorCommand.MarkIssueAsResolved(issueId),
                OrchestratorCommand.PrintLog("Regenerating architectural maps..."),
                OrchestratorCommand.GenerateKnowledgeMap,
                OrchestratorCommand.PrintLog("✅ Resolved issue `$issueId`. Picking next task..."),
                OrchestratorCommand.DeleteStateFile
            )
        )
    }

    override fun execute(env: OrchestratorEnvironment, context: OrchestratorContext, slot: SlotContext): OrchestratorState {
        val transition = evaluate(slot, OrchestratorEvent.Tick)
        val interpreter = CommandInterpreter(env, context, slot)
        for (cmd in transition.commands) {
            interpreter.interpret(cmd)
        }
        context.activeSlots.remove(slot)
        return transition.nextState
    }
}

fun isTaskTimedOut(slot: SlotContext, config: OrchestratorConfig): Boolean {
    if (slot.startTime == 0L) return false
    val now = System.currentTimeMillis()
    val elapsedMinutes = TimeUnit.MILLISECONDS.toMinutes(now - slot.startTime)
    return elapsedMinutes >= config.taskTimeoutThresholdMinutes
}

enum class RebaseOutcome {
    ALREADY_SANITIZED,
    WAITING_RETRY,
    CREATE_NEW_GENERATION
}

private fun handleRebaseAndConflicts(env: OrchestratorEnvironment, slot: SlotContext, prNumber: String, issueId: String, githubIssueNumber: String, julesSessionId: String): RebaseOutcome {
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
            return RebaseOutcome.WAITING_RETRY
        } else {
            slot.prMergeStatusAttempts = 0
            env.println("❌ Failed to retrieve PR merge status after retries. Aborting current check iteration and waiting.")
            slot.retryAfterTime = currentTime + TimeUnit.SECONDS.toMillis(env.config.pollingIntervalSeconds)
            return RebaseOutcome.WAITING_RETRY
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
                env.println("⚠️ PR #$prNumber is behind master or conflicting at SHA ${currentSha.take(7)}. Waiting for manual resolution or new commit: $prUrl")
                slot.lastWaitingLogTime = now
            }
            return RebaseOutcome.ALREADY_SANITIZED
        }

        slot.failedRebaseHeadSha = currentSha
        val prUrl = env.gitHubClient.getPrUrl(prNumber)
        val reason = if (isConflicting) "has conflicts" else "is behind master"
        env.sendNotification("⚠️ *PR #$prNumber $reason!* Automated rebasing is disabled. Transitioning to Generation ${slot.generation + 1} for manual or bot resolution.")
        env.println("\u001B[1;31m🔔 [CONFLICT] PR #$prNumber $reason! Transitioning to Generation ${slot.generation + 1}.\u001B[0m")
        return RebaseOutcome.CREATE_NEW_GENERATION
    }

    slot.lastSanitizedRebaseSha = env.gitHubClient.getPrHeadSha(prNumber)
    return RebaseOutcome.ALREADY_SANITIZED
}
