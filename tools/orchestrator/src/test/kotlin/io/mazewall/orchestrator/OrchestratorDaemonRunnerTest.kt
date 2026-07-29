package io.mazewall.orchestrator

import java.io.File
import kotlin.test.*

class OrchestratorDaemonRunnerTest {

    private var tempDir: File = File("")
    private var stateFile: File = File("")

    @BeforeTest
    fun setUp() {
        tempDir = File.createTempFile("daemon-runner-test-", "")
        tempDir.delete()
        tempDir.mkdirs()
        stateFile = File(tempDir, "state.properties")
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testLoadStateNonExistent() {
        val env = MockOrchestratorEnvironment()
        val runner = OrchestratorDaemonRunner(env, stateFile)
        runner.loadState()
        // No error, clean pass
        assertTrue(env.printlns.isEmpty())
    }

    @Test
    fun testSaveAndLoadState() {
        val env = MockOrchestratorEnvironment()
        val runner = OrchestratorDaemonRunner(env, stateFile)
        runner.context.currentIssueId = "issue-saved"
        runner.context.currentIssueTitle = "Saved Title"
        runner.saveState()

        assertTrue(stateFile.exists())

        val loader = OrchestratorDaemonRunner(env, stateFile)
        loader.loadState()
        assertEquals("issue-saved", loader.context.currentIssueId)
        assertEquals("Saved Title", loader.context.currentIssueTitle)
        assertTrue(env.printlns.any { it.contains("State machine context loaded") })
    }

    @Test
    fun testSelectAndStartTasksWithForceTask() {
        val env = MockOrchestratorEnvironment()
        val runner = OrchestratorDaemonRunner(env, stateFile)

        val issue = BacklogIssue(
            file = File(tempDir, "issue-1.md"),
            id = "issue-1",
            title = "Title 1",
            priority = 10,
            status = "open",
            dependencies = emptyList()
        )
        env.issues.add(issue)

        // Mock FORCE_TASK env variable
        val customEnv = object : OrchestratorEnvironment by env {
            override fun getEnvOrNull(key: String): String? = if (key == "FORCE_TASK") "issue-1" else null
        }

        val runnerForced = OrchestratorDaemonRunner(customEnv, stateFile)
        runnerForced.selectAndStartTasks()

        assertEquals(1, runnerForced.context.activeSlots.size)
        assertEquals("issue-1", runnerForced.context.activeSlots[0].currentIssueId)
        assertTrue(runnerForced.context.activeSlots[0].state is PendingApprovalState)
    }

    @Test
    fun testSelectAndStartTasksStandardAndConflicts() {
        val env = MockOrchestratorEnvironment()
        val runner = OrchestratorDaemonRunner(env, stateFile)

        // Active task with specific files/modules
        val activeIssue = BacklogIssue(
            file = File(tempDir, "issue-active.md"),
            id = "issue-active",
            title = "Active Title",
            priority = 10,
            status = "open",
            dependencies = emptyList(),
            targetFiles = listOf("fileA.kt"),
            targetModules = listOf(":enforcer")
        )
        env.issues.add(activeIssue)

        // Pre-populate active slot
        val activeSlot = SlotContext("issue-active")
        runner.context.activeSlots.add(activeSlot)

        // Conflict candidate: shares modules with active slot
        val conflictIssue = BacklogIssue(
            file = File(tempDir, "issue-conflict.md"),
            id = "issue-conflict",
            title = "Conflict Title",
            priority = 8,
            status = "open",
            dependencies = emptyList(),
            targetFiles = listOf("fileB.kt"),
            targetModules = listOf(":enforcer")
        )
        env.issues.add(conflictIssue)

        // Non-conflict candidate: completely disjoint modules/files
        val cleanIssue = BacklogIssue(
            file = File(tempDir, "issue-clean.md"),
            id = "issue-clean",
            title = "Clean Title",
            priority = 5,
            status = "open",
            dependencies = emptyList(),
            targetFiles = listOf("fileC.kt"),
            targetModules = listOf(":profiler")
        )
        env.issues.add(cleanIssue)

        runner.selectAndStartTasks()

        // Active slot plus the newly started non-conflicting slot
        assertEquals(2, runner.context.activeSlots.size)
        assertTrue(runner.context.activeSlots.any { it.currentIssueId == "issue-clean" })
        assertFalse(runner.context.activeSlots.any { it.currentIssueId == "issue-conflict" })
    }
}
