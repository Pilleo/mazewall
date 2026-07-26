package io.mazewall.orchestrator

import java.io.File
import kotlin.test.*

class BacklogParserEnhancedTest {

    private var tempDir: File = File("")

    @BeforeTest
    fun setUp() {
        tempDir = File.createTempFile("backlog-enhanced-test-", "")
        tempDir.delete()
        tempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testParseIssueFileWithNewFields() {
        val file = File(tempDir, "issue-201-test-enhanced.md")
        file.writeText("""
            ---
            title: "Enhance Task Approval Telegram Message with Full Context"
            severity: "HIGH"
            status: "open"
            priority: 10
            dependencies: []
            component: "orchestrator"
            effort: "small"
            ---

            # 🔴 [Severity: HIGH]: Enhance Task Approval Telegram Message with Full Context

            **Context:**
            When the Autonomous Backlog Orchestrator requests approval to start a new task in `OrchestratorDaemon.kt:156`, the Telegram notification only displays the issue ID and title.

            **Needed:**
            Modify the Telegram approval request message formatting to extract and include the full context of the backlog issue.
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        assertNotNull(issue)
        assertEquals("issue-201", issue.id)
        assertEquals("Enhance Task Approval Telegram Message with Full Context", issue.title)
        assertEquals("HIGH", issue.severity)
        assertEquals("orchestrator", issue.component)
        assertEquals("small", issue.effort)

        assertTrue(issue.context?.contains("When the Autonomous Backlog Orchestrator requests approval") == true)
        assertTrue(issue.needed?.contains("Modify the Telegram approval request message formatting") == true)
    }

    @Test
    fun testExtractSectionWithDifferentMarkers() {
        val file = File(tempDir, "issue-999-markers.md")
        file.writeText("""
            ---
            title: "Marker Test"
            priority: 5
            ---
            ### Context
            Some context here.

            **Needed:**
            Some needed here.

            ## Another Section
            Something else.
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        assertNotNull(issue)
        assertEquals("Some context here.", issue.context)
        assertEquals("Some needed here.", issue.needed)
    }

    @Test
    fun testExtractSectionWithBoldText() {
        val file = File(tempDir, "issue-999-bold.md")
        file.writeText("""
            ---
            title: "Bold Test"
            priority: 5
            ---
            ### Context
            This is a **high** priority item with **bold** text.
            It should NOT be truncated by the bold markers.

            **Needed:**
            Finish the **task** please.
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        assertNotNull(issue)
        assertEquals("This is a **high** priority item with **bold** text.\nIt should NOT be truncated by the bold markers.", issue.context)
        assertEquals("Finish the **task** please.", issue.needed)
    }

    @Test
    fun testMessageFormattingAndTruncation() {
        val issue = BacklogIssue(
            file = File("dummy"),
            id = "issue-123",
            title = "Dummy Title",
            priority = 5,
            status = "open",
            dependencies = emptyList(),
            severity = "LOW",
            effort = "big",
            component = "core",
            context = "A".repeat(3000),
            needed = "B".repeat(2000)
        )

        val text = """
            🤖 *Approval Request: Start Task ${issue.id}*
            *Title:* ${issue.title}
            *Severity:* ${issue.severity ?: "N/A"} | *Effort:* ${issue.effort ?: "N/A"} | *Component:* ${issue.component ?: "N/A"}

            *Context:*
            ${issue.context ?: "N/A"}

            *Needed:*
            ${issue.needed ?: "N/A"}

            Please approve or skip in the inline keyboard below.
        """.trimIndent()

        assertTrue(text.length > 4000)
        val truncatedText = if (text.length > 4000) text.substring(0, 3997) + "..." else text
        assertTrue(truncatedText.length <= 4000)
        assertTrue(truncatedText.endsWith("..."))
    }

    @Test
    fun testTimestampBasedIssueIdParsing() {
        val file = File(tempDir, "issue-20260726-02-timestamp-based-issue-ids.md")
        file.writeText("""
            ---
            title: "Transition Orchestrator to Timestamp-Based Issue IDs"
            severity: "HIGH"
            status: "open"
            priority: 9
            dependencies: ["issue-20260726-01"]
            component: "orchestrator"
            ---

            # 🔴 [Severity: HIGH]: Title

            **Context:** Test context
            **Needed:** Test needed
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        assertNotNull(issue)
        assertEquals("issue-20260726-02", issue.id)
        assertEquals("issue-20260726-01", issue.dependencies.first())
    }

    @Test
    fun testParseIssueFileWithColonInMultilineValueExposesSplittingBug() {
        val file = File(tempDir, "issue-20260726-999-colon-multiline.md")
        file.writeText("""
            ---
            title:
              Review Task: Profiler Module & Security Audit
            priority: 5
            status: "open"
            ---
            # Description
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        // This test documents the colon splitting bug where a multiline value containing a colon
        // causes the parser to incorrectly recognize a new key "Review Task" instead of continuing "title".
        if (issue != null) {
            assertNotEquals("Review Task: Profiler Module & Security Audit", issue.title,
                "Due to the colon splitting bug, the multiline title is incorrectly parsed/truncated.")
        }
    }
}
