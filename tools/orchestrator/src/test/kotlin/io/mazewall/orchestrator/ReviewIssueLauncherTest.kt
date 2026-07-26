package io.mazewall.orchestrator

import java.io.File
import kotlin.test.*

class ReviewIssueLauncherTest {

    private var tempDir: File = File("")

    @BeforeTest
    fun setUp() {
        tempDir = File.createTempFile("review-launcher-test-", "")
        tempDir.delete()
        tempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testReviewTaskCreatedWithRequiredSkillHeaderAndImmediateSlotLaunch() {
        val env = MockOrchestratorEnvironment()
        val context = OrchestratorContext()

        val comments = "Focus specifically on MemorySegment lifetime closure leaks and reactor loop IO handling."
        val issue = ReviewIssueLauncher.launchReviewTask(comments, tempDir, env, context)

        // 1. Verify issue properties
        assertNotNull(issue)
        assertEquals(10, issue.priority)
        assertEquals("in_progress", issue.status)
        assertEquals("profiler", issue.component)
        assertEquals(listOf(":profiler", ":enforcer"), issue.targetModules)

        // 2. Verify prompt body starts strictly with required skill header
        val fileContent = issue.file.readText()
        assertTrue(fileContent.contains("Please review profiler module using .agents/skills/review/SKILL.md skill. Create issues using skill .agents/skills/create_backlog_issue/SKILL.md"))
        assertTrue(fileContent.contains("Additional Focus Instructions:"))
        assertTrue(fileContent.contains(comments))
        assertTrue(fileContent.contains("Quality and Safety Guidelines"))

        // 3. Verify immediate slot creation
        assertEquals(1, context.activeSlots.size)
        val activeSlot = context.activeSlots.first()
        assertEquals(issue.id, activeSlot.currentIssueId)
        assertEquals(issue.title, activeSlot.currentIssueTitle)

        // 4. Verify backlog schema validation passes cleanly
        val errors = BacklogValidator.validateBacklog(tempDir)
        assertTrue(errors.isEmpty(), "Expected clean backlog validation, but got: $errors")
    }
}
