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
            priority = BacklogPriority.HIGH,
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
            priority = BacklogPriority.HIGH,
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
            priority = BacklogPriority.MEDIUM,
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
    fun testEmptyTargetsScheduledSequentially() {
        // Part 1: No active tasks. Tasks with empty target list can start.
        val env1 = MockOrchestratorEnvironment()
        val runner1 = OrchestratorDaemonRunner(env1, File(tempDir, ".state1.properties"))

        val issueEmptyModules = BacklogIssue(
            file = File(tempDir, "issue-empty-mod.md"),
            id = "issue-empty-mod",
            title = "Empty Modules Task",
            priority = BacklogPriority.HIGH,
            status = "open",
            dependencies = emptyList(),
            targetFiles = listOf("Main.kt"),
            targetModules = emptyList() // empty!
        )
        env1.issues.add(issueEmptyModules)
        runner1.selectAndStartTasks()

        assertTrue(runner1.context.activeSlots.any { it.currentIssueId == "issue-empty-mod" }, "Should select empty-mod task when nothing is active")

        // Part 2: Active task has non-empty targets, candidate task has empty targets.
        val env2 = MockOrchestratorEnvironment()
        val runner2 = OrchestratorDaemonRunner(env2, File(tempDir, ".state2.properties"))

        val issueActive = BacklogIssue(
            file = File(tempDir, "issue-active.md"),
            id = "issue-active",
            title = "Active Task",
            priority = BacklogPriority.HIGH,
            status = "open",
            dependencies = emptyList(),
            targetFiles = listOf("Enforcer.kt"),
            targetModules = listOf(":enforcer")
        )

        val candidateEmptyFiles = BacklogIssue(
            file = File(tempDir, "issue-empty-files.md"),
            id = "issue-empty-files",
            title = "Candidate Empty Files",
            priority = BacklogPriority.HIGH,
            status = "open",
            dependencies = emptyList(),
            targetFiles = emptyList(), // empty!
            targetModules = listOf(":profiler")
        )

        val candidateDistinct = BacklogIssue(
            file = File(tempDir, "issue-distinct.md"),
            id = "issue-distinct",
            title = "Candidate Distinct",
            priority = BacklogPriority.MEDIUM,
            status = "open",
            dependencies = emptyList(),
            targetFiles = listOf("Profiler.kt"),
            targetModules = listOf(":profiler")
        )

        env2.issues.addAll(listOf(issueActive, candidateEmptyFiles, candidateDistinct))

        // Set issue-active as already active
        val slotActive = SlotContext("issue-active").apply {
            githubIssueNumber = "1"
            julesSessionId = "s1"
            prNumber = "101"
            state = CiRunningState("issue-active", "1", "s1", "101")
        }
        runner2.context.activeSlots.add(slotActive)

        runner2.selectAndStartTasks()

        val activeIds2 = runner2.context.activeSlots.map { it.currentIssueId }.toSet()
        assertTrue(activeIds2.contains("issue-distinct"), "Distinct task with non-empty targets should start")
        assertFalse(activeIds2.contains("issue-empty-files"), "Task with empty target files should NOT start in parallel with active tasks")

        // Part 3: Active task has empty targets, candidate has non-empty targets.
        val env3 = MockOrchestratorEnvironment()
        val runner3 = OrchestratorDaemonRunner(env3, File(tempDir, ".state3.properties"))

        val issueActiveEmpty = BacklogIssue(
            file = File(tempDir, "issue-active-empty.md"),
            id = "issue-active-empty",
            title = "Active Empty Target Task",
            priority = BacklogPriority.HIGH,
            status = "open",
            dependencies = emptyList(),
            targetFiles = emptyList(), // empty!
            targetModules = listOf(":enforcer")
        )

        val candidateNonEmpty = BacklogIssue(
            file = File(tempDir, "issue-non-empty.md"),
            id = "issue-non-empty",
            title = "Candidate Non-Empty",
            priority = BacklogPriority.HIGH,
            status = "open",
            dependencies = emptyList(),
            targetFiles = listOf("Profiler.kt"),
            targetModules = listOf(":profiler")
        )

        env3.issues.addAll(listOf(issueActiveEmpty, candidateNonEmpty))

        // Set issueActiveEmpty as already active
        val slotActiveEmpty = SlotContext("issue-active-empty").apply {
            githubIssueNumber = "2"
            julesSessionId = "s2"
            prNumber = "102"
            state = CiRunningState("issue-active-empty", "2", "s2", "102")
        }
        runner3.context.activeSlots.add(slotActiveEmpty)

        runner3.selectAndStartTasks()

        val activeIds3 = runner3.context.activeSlots.map { it.currentIssueId }.toSet()
        assertFalse(activeIds3.contains("issue-non-empty"), "Should NOT start any parallel task when active task has empty targets")
    }

    @Test
    fun testNonInterferingEmptyTargetTasksCanRunConcurrently() {
        val env = MockOrchestratorEnvironment()
        val runner = OrchestratorDaemonRunner(env, File(tempDir, ".state.noninterfering.properties"))

        // Active task is a standard, module-specific task
        val activeIssue = BacklogIssue(
            file = File(tempDir, "issue-active.md"),
            id = "issue-active",
            title = "Active module task",
            priority = BacklogPriority.HIGH,
            status = "open",
            dependencies = emptyList(),
            targetFiles = listOf("Enforcer.kt"),
            targetModules = listOf(":enforcer")
        )

        // Non-interfering empty-target candidate: e.g. a documentation component
        val nonInterferingCandidate = BacklogIssue(
            file = File(tempDir, "issue-noninterfering.md"),
            id = "issue-noninterfering",
            title = "Docs Update Task",
            priority = BacklogPriority.HIGH,
            status = "open",
            dependencies = emptyList(),
            targetFiles = emptyList(),
            targetModules = emptyList(),
            component = "docs"
        )

        // Non-interfering empty-target candidate 2: e.g. a review task with "review-task" in the ID
        val nonInterferingCandidate2 = BacklogIssue(
            file = File(tempDir, "issue-review-task.md"),
            id = "review-task-something",
            title = "Review Task",
            priority = BacklogPriority.MEDIUM,
            status = "open",
            dependencies = emptyList(),
            targetFiles = emptyList(),
            targetModules = emptyList()
        )

        // Interfering empty-target candidate: not non-interfering, should be blocked
        val interferingCandidate = BacklogIssue(
            file = File(tempDir, "issue-interfering.md"),
            id = "issue-interfering",
            title = "Interfering empty target task",
            priority = BacklogPriority.MEDIUM,
            status = "open",
            dependencies = emptyList(),
            targetFiles = emptyList(),
            targetModules = emptyList(),
            component = "enforcer" // Not a non-interfering component
        )

        env.issues.addAll(listOf(activeIssue, nonInterferingCandidate, nonInterferingCandidate2, interferingCandidate))

        // Set activeIssue as already active
        val slotActive = SlotContext("issue-active").apply {
            githubIssueNumber = "1"
            julesSessionId = "s1"
            prNumber = "101"
            state = CiRunningState("issue-active", "1", "s1", "101")
        }
        runner.context.activeSlots.add(slotActive)

        runner.selectAndStartTasks()

        val activeIds = runner.context.activeSlots.map { it.currentIssueId }.toSet()
        assertTrue(activeIds.contains("issue-noninterfering"), "Non-interfering empty-target task should start concurrently")
        assertTrue(activeIds.contains("review-task-something"), "Review empty-target task should start concurrently")
        assertFalse(activeIds.contains("issue-interfering"), "Interfering empty-target task should NOT start concurrently with active tasks")
    }

    @Test
    fun testSerializationOfMultipleSlots() {
        val context = OrchestratorContext()

        val slot1 = SlotContext("issue-1").apply {
            githubIssueNumber = "1"
            julesSessionId = "s1"
            prNumber = "101"
            state = CiRunningState("issue-1", "1", "s1", "101")
            currentIssueTitle = "Title 1"
            lastBuildStatus = "SUCCESS"
        }
        val slot2 = SlotContext("issue-2").apply {
            currentIssueTitle = "Title 2"
            currentIssueFile = "issue-2.md"
            state = PendingApprovalState("issue-2", "Title 2", "issue-2.md")
        }

        context.activeSlots.addAll(listOf(slot1, slot2))

        val props = java.util.Properties()
        context.save(props)

        val loadedContext = OrchestratorContext()
        loadedContext.load(props)

        assertEquals(2, loadedContext.activeSlots.size)

        val loadedSlot1 = loadedContext.activeSlots.firstOrNull { it.currentIssueId == "issue-1" }
        assertNotNull(loadedSlot1)
        assertTrue(loadedSlot1.state is CiRunningState)
        assertEquals("Title 1", loadedSlot1.currentIssueTitle)
        assertEquals("101", loadedSlot1.prNumber)
        assertEquals("SUCCESS", loadedSlot1.lastBuildStatus)

        val loadedSlot2 = loadedContext.activeSlots.firstOrNull { it.currentIssueId == "issue-2" }
        assertNotNull(loadedSlot2)
        assertTrue(loadedSlot2.state is PendingApprovalState)
        assertEquals("Title 2", loadedSlot2.currentIssueTitle)
    }
}
