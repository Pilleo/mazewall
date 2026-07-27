package io.mazewall.orchestrator

import java.io.File
import kotlin.test.*

class ParallelTaskSchedulerTest {

    private var tempDir: File = File("")

    @BeforeTest
    fun setUp() {
        tempDir = File.createTempFile("parallel-scheduler-test-", "")
        tempDir.delete()
        tempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testConflictFreeSchedulerExcludesConflicts() {
        val env = MockOrchestratorEnvironment()
        val runner = OrchestratorDaemonRunner(env, File(tempDir, ".state.properties"))

        // Add 3 open backlog issues:
        // Issue A: target_modules = [:enforcer], target_files = [Enforcer.kt]
        val issueA = BacklogIssue(
            file = File(tempDir, "issue-a.md"),
            id = "issue-a",
            title = "Task A",
            priority = 10,
            status = "open",
            dependencies = emptyList(),
            targetFiles = listOf("Enforcer.kt"),
            targetModules = listOf(":enforcer")
        )
        // Issue B: target_modules = [:profiler], target_files = [Profiler.kt] (No conflict with A)
        val issueB = BacklogIssue(
            file = File(tempDir, "issue-b.md"),
            id = "issue-b",
            title = "Task B",
            priority = 8,
            status = "open",
            dependencies = emptyList(),
            targetFiles = listOf("Profiler.kt"),
            targetModules = listOf(":profiler")
        )
        // Issue C: target_modules = [:enforcer], target_files = [Main.kt] (Conflicts with A on module :enforcer)
        val issueC = BacklogIssue(
            file = File(tempDir, "issue-c.md"),
            id = "issue-c",
            title = "Task C",
            priority = 6,
            status = "open",
            dependencies = emptyList(),
            targetFiles = listOf("Main.kt"),
            targetModules = listOf(":enforcer")
        )

        env.issues.addAll(listOf(issueA, issueB, issueC))

        // Trigger task selection inside runner
        runner.selectAndStartTasks()

        // Verify which tasks got started
        // Both A (highest priority) and B (no conflict) should be selected and started.
        // C should NOT be selected because it conflicts with A on :enforcer module!
        val startedIds = env.issues.filter { issue ->
            runner.context.activeSlots.any { it.currentIssueId == issue.id }
        }.map { it.id }.toSet()

        assertTrue(startedIds.contains("issue-a"), "Should select issue-a")
        assertTrue(startedIds.contains("issue-b"), "Should select issue-b (no conflict with issue-a)")
        assertFalse(startedIds.contains("issue-c"), "Should NOT select issue-c (conflicts on module :enforcer)")
    }

    @Test
    fun testSerializationOfMultipleSlots() {
        val context = OrchestratorContext()

        val slot1 = SlotContext("issue-1").apply {
            state = OrchestratorState.CI_RUNNING
            currentIssueTitle = "Title 1"
            prNumber = "101"
            lastBuildStatus = "SUCCESS"
        }
        val slot2 = SlotContext("issue-2").apply {
            state = OrchestratorState.PENDING_APPROVAL
            currentIssueTitle = "Title 2"
        }

        context.activeSlots.addAll(listOf(slot1, slot2))

        val props = java.util.Properties()
        context.save(props)

        val loadedContext = OrchestratorContext()
        loadedContext.load(props)

        assertEquals(2, loadedContext.activeSlots.size)

        val loadedSlot1 = loadedContext.activeSlots.firstOrNull { it.currentIssueId == "issue-1" }
        assertNotNull(loadedSlot1)
        assertEquals(OrchestratorState.CI_RUNNING, loadedSlot1.state)
        assertEquals("Title 1", loadedSlot1.currentIssueTitle)
        assertEquals("101", loadedSlot1.prNumber)
        assertEquals("SUCCESS", loadedSlot1.lastBuildStatus)

        val loadedSlot2 = loadedContext.activeSlots.firstOrNull { it.currentIssueId == "issue-2" }
        assertNotNull(loadedSlot2)
        assertEquals(OrchestratorState.PENDING_APPROVAL, loadedSlot2.state)
        assertEquals("Title 2", loadedSlot2.currentIssueTitle)
    }
}
