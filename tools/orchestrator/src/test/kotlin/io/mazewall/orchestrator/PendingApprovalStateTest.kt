package io.mazewall.orchestrator

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PendingApprovalStateTest {
    private class MockEnvironment : OrchestratorEnvironment {
        override val config = OrchestratorConfig()
        val printedMessages = mutableListOf<String>()
        val errorMessages = mutableListOf<String>()
        val notifications = mutableListOf<String>()

        override fun println(message: Any?) {
            printedMessages.add(message.toString())
        }

        override fun print(message: Any?) {}

        override fun errPrintln(message: Any?) {
            errorMessages.add(message.toString())
        }

        override fun sleep(
            duration: Long,
            unit: TimeUnit,
        ) {}

        override fun ringBell(times: Int) {}

        override fun readLine(): String? = "y"

        override fun getEnvOrNull(key: String): String? = null

        override fun sendNotification(message: String) {
            notifications.add(message)
        }

        override fun requestApproval(
            issueId: String,
            text: String,
        ): Boolean = true

        override fun sendApprovalRequest(
            issueId: String,
            text: String,
        ) {}

        override fun checkApprovalNonBlocking(issueId: String): Boolean? = true

        override fun pollTelegramUpdates(context: OrchestratorContext) {}

        override val gitHubClient = object : GitHubClient {
            override fun getPrMergeStatus(prNumber: String): PrMergeStatus = PrMergeStatus("MERGEABLE", 0)

            override fun findExistingIssueNumber(issueId: String): String? = null

            override fun createIssue(
                title: String,
                body: String,
                label: String,
            ): String = "123"

            override fun getRepoName(): String = "mock/repo"

            override fun addLabel(
                issueNumber: String,
                label: String,
            ) {}

            override fun ensureLabelExists(label: String) {}

            override fun labelPr(
                prNumber: String,
                label: String,
            ) {}

            override fun isIssueClosed(issueNumber: String): Boolean = false

            override fun isPrClosed(prNumber: String): Boolean = false

            override fun findLinkedPR(
                issueNumber: String,
                issueId: String,
                julesSessionId: String?,
            ): String? = null

            override fun isPrMerged(prNumber: String): Boolean = false

            override fun getPrHeadSha(prNumber: String): String = "sha123"

            override fun checkBuildStatus(prNumber: String): String = "SUCCESS"

            override fun getPrComments(prNumber: String): List<GitHubComment> = emptyList()

            override fun commentOnPr(
                prNumber: String,
                body: String,
            ) {}

            override fun commentOnIssue(
                issueNumber: String,
                body: String,
            ) {}

            override fun getPrDiff(prNumber: String): String = "mock diff"

            override fun getFailedBuildLogs(prNumber: String): String = "mock failed logs"

            override fun getPrUrl(prNumber: String): String = "mock url"

            override fun isCommitEmpty(
                prNumber: String,
                shaOld: String,
                shaNew: String,
            ): Boolean = false

            override fun clearPrCache(prNumber: String) {}
        }

        override val julesClient = object : JulesClient {
            override fun getActiveSession(issueId: String): JulesSession? = null

            override fun getSessionStatusFromActivities(sessionId: String): String? = null

            override fun hasUnableToCompleteActivity(sessionId: String): Boolean = false

            override fun triggerSession(
                repo: String,
                issueId: String,
                prompt: String,
            ): JulesSession {
                return JulesSession("s1", "desc", repo, "PENDING")
            }

            override fun createSessionWithContext(
                repo: String,
                issueId: String,
                githubIssueNumber: String,
                previousPrUrl: String,
                previousBranch: String,
                originalTaskDescription: String,
            ): JulesSession {
                return JulesSession("s1", "desc", repo, "PENDING")
            }

            override fun sendSessionMessage(
                sessionId: String,
                prompt: String,
            ) {}

            override fun listSessions(): List<JulesSession> = emptyList()

            override fun getSessionPatch(sessionId: String): String? = null
        }

        override fun parseAllIssues(): List<BacklogIssue> = emptyList()

        override fun writeGithubIssue(
            issue: BacklogIssue,
            number: Int,
        ) {}

        override fun removeGithubIssue(issue: BacklogIssue) {}

        override fun markIssueAsResolved(issue: BacklogIssue) {}

        override fun markIssueAsDeferred(issue: BacklogIssue) {}

        override fun deleteStateFile() {}

        override fun generateKnowledgeMap() {}
    }

    @Test
    fun `skipsIssueWithMissingContext`() {
        val tempFile = File.createTempFile("issue-missing-context", ".md")
        tempFile.writeText(
            """
            ---
            title: "Test Issue"
            id: issue-missing-context
            priority: high
            status: open
            component: enforcer
            ---

            **Needed:**
            This is the needed section.
        """.trimIndent(),
        )
        try {
            val env = MockEnvironment()
            val context = OrchestratorContext()
            val slot = SlotContext("issue-missing-context")
            context.activeSlots.add(slot)

            val state = PendingApprovalState("issue-missing-context", "Test Issue", tempFile.absolutePath)
            val nextState = state.execute(env, context, slot)

            // Verify that it returns SelectTaskState
            assertTrue(nextState is SelectTaskState, "Expected SelectTaskState, got ${nextState::class.simpleName}")

            // Verify that issueId was added to skippedIds
            assertTrue(context.skippedIds.contains("issue-missing-context"), "Expected issue-missing-context in skippedIds")

            // Verify that slot was removed from activeSlots
            assertFalse(context.activeSlots.contains(slot), "Expected slot to be removed from activeSlots")

            // Verify that error message was printed
            assertTrue(
                env.errorMessages.any { it.contains("issue-missing-context") && it.contains("Context or Needed") },
                "Expected error message about missing Context or Needed, got: ${env.errorMessages}",
            )

            // Verify that notification was sent
            assertTrue(
                env.notifications.any { it.contains("issue-missing-context") && it.contains("Context or Needed") },
                "Expected notification about missing Context or Needed, got: ${env.notifications}",
            )
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `skipsIssueWithMissingNeeded`() {
        val tempFile = File.createTempFile("issue-missing-needed", ".md")
        tempFile.writeText(
            """
            ---
            title: "Test Issue"
            id: issue-missing-needed
            priority: high
            status: open
            component: enforcer
            ---

            **Context:**
            This is the context section.
        """.trimIndent(),
        )
        try {
            val env = MockEnvironment()
            val context = OrchestratorContext()
            val slot = SlotContext("issue-missing-needed")
            context.activeSlots.add(slot)

            val state = PendingApprovalState("issue-missing-needed", "Test Issue", tempFile.absolutePath)
            val nextState = state.execute(env, context, slot)

            // Verify that it returns SelectTaskState
            assertTrue(nextState is SelectTaskState, "Expected SelectTaskState, got ${nextState::class.simpleName}")

            // Verify that issueId was added to skippedIds
            assertTrue(context.skippedIds.contains("issue-missing-needed"), "Expected issue-missing-needed in skippedIds")

            // Verify that slot was removed from activeSlots
            assertFalse(context.activeSlots.contains(slot), "Expected slot to be removed from activeSlots")

            // Verify that error message was printed
            assertTrue(
                env.errorMessages.any { it.contains("issue-missing-needed") && it.contains("Context or Needed") },
                "Expected error message about missing Context or Needed, got: ${env.errorMessages}",
            )

            // Verify that notification was sent
            assertTrue(
                env.notifications.any { it.contains("issue-missing-needed") && it.contains("Context or Needed") },
                "Expected notification about missing Context or Needed, got: ${env.notifications}",
            )
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `warnsForUnknownComponent`() {
        val tempFile = File.createTempFile("issue-unknown-component", ".md")
        tempFile.writeText(
            """
            ---
            title: "Test Issue"
            id: issue-unknown-component
            priority: high
            status: open
            component: unknown
            ---

            **Context:**
            This is the context section.

            **Needed:**
            This is the needed section.
        """.trimIndent(),
        )
        try {
            val env = MockEnvironment()
            val context = OrchestratorContext()
            val slot = SlotContext("issue-unknown-component")
            context.activeSlots.add(slot)

            val state = PendingApprovalState("issue-unknown-component", "Test Issue", tempFile.absolutePath)
            val nextState = state.execute(env, context, slot)

            // Should not skip the issue (validation passes)
            assertFalse(context.skippedIds.contains("issue-unknown-component"), "Should not skip issue with unknown component")

            // Verify that warning message was printed
            assertTrue(
                env.errorMessages.any { it.contains("issue-unknown-component") && it.contains("unknown component") },
                "Expected warning message about unknown component, got: ${env.errorMessages}",
            )

            // Verify that warning notification was sent
            assertTrue(
                env.notifications.any { it.contains("issue-unknown-component") && it.contains("unknown component") },
                "Expected warning notification about unknown component, got: ${env.notifications}",
            )
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `warnsForLowPriority`() {
        val tempFile = File.createTempFile("issue-low-priority", ".md")
        tempFile.writeText(
            """
            ---
            title: "Test Issue"
            id: issue-low-priority
            priority: low
            status: open
            component: enforcer
            ---

            **Context:**
            This is the context section.

            **Needed:**
            This is the needed section.
        """.trimIndent(),
        )
        try {
            val env = MockEnvironment()
            val context = OrchestratorContext()
            val slot = SlotContext("issue-low-priority")
            context.activeSlots.add(slot)

            val state = PendingApprovalState("issue-low-priority", "Test Issue", tempFile.absolutePath)
            val nextState = state.execute(env, context, slot)

            // Should not skip the issue (validation passes)
            assertFalse(context.skippedIds.contains("issue-low-priority"), "Should not skip issue with LOW priority")

            // Verify that warning message was printed
            assertTrue(
                env.errorMessages.any { it.contains("issue-low-priority") && it.contains("LOW priority") },
                "Expected warning message about LOW priority, got: ${env.errorMessages}",
            )

            // Verify that warning notification was sent
            assertTrue(
                env.notifications.any { it.contains("issue-low-priority") && it.contains("LOW priority") },
                "Expected warning notification about LOW priority, got: ${env.notifications}",
            )
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `passesValidationWithValidIssue`() {
        val tempFile = File.createTempFile("issue-valid", ".md")
        tempFile.writeText(
            """
            ---
            title: "Test Issue"
            id: issue-valid
            priority: high
            status: open
            component: enforcer
            ---

            **Context:**
            This is the context section.

            **Needed:**
            This is the needed section.
        """.trimIndent(),
        )
        try {
            val env = MockEnvironment()
            val context = OrchestratorContext()
            val slot = SlotContext("issue-valid")
            context.activeSlots.add(slot)

            val state = PendingApprovalState("issue-valid", "Test Issue", tempFile.absolutePath)
            val nextState = state.execute(env, context, slot)

            // Should not skip the issue
            assertFalse(context.skippedIds.contains("issue-valid"), "Should not skip valid issue")

            // Should not have error messages about Context or Needed
            assertFalse(
                env.errorMessages.any { it.contains("issue-valid") && it.contains("Context or Needed") },
                "Should not have error message for valid issue, got: ${env.errorMessages}",
            )

            // Should not have notifications about Context or Needed
            assertFalse(
                env.notifications.any { it.contains("issue-valid") && it.contains("Context or Needed") },
                "Should not have notification for valid issue, got: ${env.notifications}",
            )
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `skipsIssueWithBlankContext`() {
        val tempFile = File.createTempFile("issue-blank-context", ".md")
        tempFile.writeText(
            """
            ---
            title: "Test Issue"
            id: issue-blank-context
            priority: high
            status: open
            component: enforcer
            ---

            **Context:**
            

            **Needed:**
            This is the needed section.
        """.trimIndent(),
        )
        try {
            val env = MockEnvironment()
            val context = OrchestratorContext()
            val slot = SlotContext("issue-blank-context")
            context.activeSlots.add(slot)

            val state = PendingApprovalState("issue-blank-context", "Test Issue", tempFile.absolutePath)
            val nextState = state.execute(env, context, slot)

            // Verify that it returns SelectTaskState (skips the issue)
            assertTrue(nextState is SelectTaskState, "Expected SelectTaskState for blank context, got ${nextState::class.simpleName}")
            assertTrue(context.skippedIds.contains("issue-blank-context"), "Expected issue-blank-context in skippedIds")
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `skipsIssueWithBlankNeeded`() {
        val tempFile = File.createTempFile("issue-blank-needed", ".md")
        tempFile.writeText(
            """
            ---
            title: "Test Issue"
            id: issue-blank-needed
            priority: high
            status: open
            component: enforcer
            ---

            **Context:**
            This is the context section.

            **Needed:**
            
        """.trimIndent(),
        )
        try {
            val env = MockEnvironment()
            val context = OrchestratorContext()
            val slot = SlotContext("issue-blank-needed")
            context.activeSlots.add(slot)

            val state = PendingApprovalState("issue-blank-needed", "Test Issue", tempFile.absolutePath)
            val nextState = state.execute(env, context, slot)

            // Verify that it returns SelectTaskState (skips the issue)
            assertTrue(nextState is SelectTaskState, "Expected SelectTaskState for blank needed, got ${nextState::class.simpleName}")
            assertTrue(context.skippedIds.contains("issue-blank-needed"), "Expected issue-blank-needed in skippedIds")
        } finally {
            tempFile.delete()
        }
    }
}
