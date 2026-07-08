package io.mazewall.orchestrator

import java.io.File
import kotlin.test.*

class OrchestratorTest {

    private var tempDir: File = File("")

    @BeforeTest
    fun setUp() {
        tempDir = File.createTempFile("backlog-test-", "")
        tempDir.delete()
        tempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testParseIssueFileWithInlineDependencies() {
        val file = File(tempDir, "issue-001-test-inline.md")
        file.writeText("""
            ---
            title: "Test Inline Deps"
            priority: 7
            status: "open"
            dependencies: ["issue-002", "issue-003"]
            github_issue: 1234
            ---
            # Description
            Hello World.
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        assertNotNull(issue)
        assertEquals("issue-001", issue.id)
        assertEquals("Test Inline Deps", issue.title)
        assertEquals(7, issue.priority)
        assertEquals("open", issue.status)
        assertEquals(listOf("issue-002", "issue-003"), issue.dependencies)
        assertEquals(1234, issue.githubIssue)
    }

    @Test
    fun testParseIssueFileWithMultilineDependencies() {
        val file = File(tempDir, "issue-002-test-multi.md")
        file.writeText("""
            ---
            title: 'Test Multiline Deps'
            priority: 4
            status: 'open'
            dependencies:
              - issue-004
              - issue-005
            ---
            # Description
            Multiline parser testing.
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        assertNotNull(issue)
        assertEquals("issue-002", issue.id)
        assertEquals("Test Multiline Deps", issue.title)
        assertEquals(4, issue.priority)
        assertEquals("open", issue.status)
        assertEquals(listOf("issue-004", "issue-005"), issue.dependencies)
        assertNull(issue.githubIssue)
    }

    @Test
    fun testDependencyGraphPriorityAndBlocking() {
        val issue1 = BacklogIssue(
            file = File(tempDir, "issue-001.md"),
            id = "issue-001",
            title = "Task 1",
            priority = 5,
            status = "open",
            dependencies = listOf("issue-002") // Blocked by issue-002
        )
        val issue2 = BacklogIssue(
            file = File(tempDir, "issue-002.md"),
            id = "issue-002",
            title = "Task 2",
            priority = 2,
            status = "open",
            dependencies = emptyList() // Unblocked
        )
        val issue3 = BacklogIssue(
            file = File(tempDir, "issue-003.md"),
            id = "issue-003",
            title = "Task 3",
            priority = 8,
            status = "open",
            dependencies = emptyList() // Unblocked, higher priority than 2
        )

        val issues = listOf(issue1, issue2, issue3)
        val next = DependencyGraph.selectNextIssue(issues)

        assertNotNull(next)
        // Task 3 should be selected since it has priority 8 and is unblocked, even though Task 1 has dependencies but is blocked
        assertEquals("issue-003", next.id)
    }

    @Test
    fun testDependencyGraphTieBreaker() {
        val issue1 = BacklogIssue(
            file = File(tempDir, "issue-001.md"),
            id = "issue-001",
            title = "Task 1",
            priority = 5,
            status = "open",
            dependencies = emptyList()
        )
        val issue2 = BacklogIssue(
            file = File(tempDir, "issue-002.md"),
            id = "issue-002",
            title = "Task 2",
            priority = 5,
            status = "open",
            dependencies = emptyList()
        )

        val issues = listOf(issue2, issue1) // Out of order
        val next = DependencyGraph.selectNextIssue(issues)

        assertNotNull(next)
        // Should resolve to issue-002 due to alphabetical tie-breaker (higher ID first, freshest issues first)
        assertEquals("issue-002", next.id)
    }

    @Test
    fun testDependencyGraphFiltersOutSkippedStatus() {
        val issue = BacklogIssue(
            file = File(tempDir, "issue-001.md"),
            id = "issue-001",
            title = "Task 1",
            priority = 5,
            status = "resolved", // Not open
            dependencies = emptyList()
        )

        val next = DependencyGraph.selectNextIssue(listOf(issue))
        assertNull(next)
    }
}
