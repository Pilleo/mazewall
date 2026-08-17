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
            priority: high
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
    fun testMarkIssueAsDeferredPersistsTerminalStatus() {
        val file = File(tempDir, "issue-20260810-150801-timeout.md")
        file.writeText(
            """
            ---
            title: "Timed-out task"
            severity: "MEDIUM"
            status: "in_progress"
            priority: high
            dependencies: []
            component: "orchestrator"
            effort: "small"
            ---
            """.trimIndent()
        )
        val issue = assertNotNull(BacklogParser.parseIssueFile(file))

        BacklogParser.markIssueAsDeferred(issue)

        assertEquals("deferred", BacklogParser.parseIssueFile(file)?.status)
    }

    @Test
    fun testMarkIssueAsDeferredAcceptsOpenStatus() {
        val file = File(tempDir, "issue-20260816-open-timeout.md")
        file.writeText(
            """
            ---
            title: "Timed-out open task"
            severity: "MEDIUM"
            status: "open"
            priority: high
            dependencies: []
            component: "orchestrator"
            effort: "small"
            ---
            """.trimIndent()
        )
        val issue = assertNotNull(BacklogParser.parseIssueFile(file))

        BacklogParser.markIssueAsDeferred(issue)

        assertEquals("deferred", BacklogParser.parseIssueFile(file)?.status)
    }

    @Test
    fun testExtractSectionWithDifferentMarkers() {
        val file = File(tempDir, "issue-999-markers.md")
        file.writeText("""
            ---
            title: "Marker Test"
            priority: medium
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
            priority: medium
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
            priority = BacklogPriority.MEDIUM,
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
            priority: high
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
            priority: medium
            status: "open"
            ---
            # Description
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        assertNotNull(issue)
        assertEquals("Review Task: Profiler Module & Security Audit", issue.title,
            "The multiline title containing a colon should be correctly parsed.")
    }

    @Test
    fun testParseIssueFileWithColonInQuotedValue() {
        val file = File(tempDir, "issue-20260726-998-colon-quoted.md")
        file.writeText("""
            ---
            title: "Review Task: Profiler Module & Security Audit"
            priority: medium
            status: "open"
            ---
            # Description
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        assertNotNull(issue)
        assertEquals("Review Task: Profiler Module & Security Audit", issue.title,
            "Quoted value with a colon should be parsed completely without truncation.")
    }

    @Test
    fun testParseListWithMultiline() {
        val file = File(tempDir, "issue-multiline-list.md")
        file.writeText("""
            ---
            title: "Multiline List Test"
            priority: medium
            status: "open"
            target_files:
              - "file1.txt"
              - "file2.txt"
            ---
            # Description
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        assertNotNull(issue)
        assertEquals(2, issue.targetFiles.size)
        assertEquals("file1.txt", issue.targetFiles[0])
        assertEquals("file2.txt", issue.targetFiles[1])
    }

    @Test
    fun testParseListWithNestedAndEscapedQuotes() {
        val file = File(tempDir, "issue-quotes-list.md")
        file.writeText("""
            ---
            title: "Quotes List Test"
            priority: medium
            status: "open"
            target_files: ["\"file1.txt\"", "'file2.txt'", "\'file3.txt\'"]
            ---
            # Description
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        assertNotNull(issue)
        assertEquals(3, issue.targetFiles.size)
        assertEquals("file1.txt", issue.targetFiles[0])
        assertEquals("file2.txt", issue.targetFiles[1])
        assertEquals("file3.txt", issue.targetFiles[2])
    }

    @Test
    fun testParseIssueFileWithMissingRequiredPriority() {
        val file = File(tempDir, "issue-invalid-priority.md")
        file.writeText("""
            ---
            title: "Invalid Priority"
            priority: "not-an-integer"
            status: "open"
            ---
            # Description
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        assertNull(issue) // should catch IllegalArgumentException and print error, returning null
    }

    @Test
    fun testParseAllIssuesNonExistentDir() {
        val nonExistent = File(tempDir, "non-existent-sub-dir")
        val issues = BacklogParser.parseAllIssues(nonExistent)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun testWriteAndRemoveGithubIssue() {
        val file = File(tempDir, "issue-123.md")
        file.writeText("""
            ---
            title: "Issue 123"
            priority: medium
            status: "open"
            ---
            # Description
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        assertNotNull(issue)

        // Write
        BacklogParser.writeGithubIssue(issue, 999)
        val afterWrite = BacklogParser.parseIssueFile(file)
        assertNotNull(afterWrite)
        assertEquals(999, afterWrite.githubIssue)

        // Remove
        BacklogParser.removeGithubIssue(afterWrite)
        val afterRemove = BacklogParser.parseIssueFile(file)
        assertNotNull(afterRemove)
        assertNull(afterRemove.githubIssue)
    }

    @Test
    fun testMarkIssueAsResolved() {
        val file = File(tempDir, "issue-123-resolve.md")
        file.writeText("""
            ---
            title: "Resolve Test"
            priority: medium
            status: "open"
            ---
            # Description
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        assertNotNull(issue)

        val resolvedDir = File(tempDir, "resolved")
        BacklogParser.markIssueAsResolved(issue, resolvedDir)

        assertFalse(file.exists())
        val movedFile = File(resolvedDir, "issue-123-resolve.md")
        assertTrue(movedFile.exists())

        val resolvedIssue = BacklogParser.parseIssueFile(movedFile)
        assertNotNull(resolvedIssue)
        assertEquals("resolved", resolvedIssue.status)
    }

    @Test
    fun testExtractSectionMissingSection() {
        val file = File(tempDir, "issue-no-section.md")
        file.writeText("""
            ---
            title: "No Section"
            priority: medium
            status: "open"
            ---
            # Description
        """.trimIndent())

        val issue = BacklogParser.parseIssueFile(file)
        assertNotNull(issue)
        assertNull(issue.context)
        assertNull(issue.needed)
    }
}
