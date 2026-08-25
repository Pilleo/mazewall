package io.mazewall.orchestrator

import java.io.File
import kotlin.test.*

class BacklogValidatorTest {

    private var tempDir: File = File("")

    @BeforeTest
    fun setUp() {
        tempDir = File.createTempFile("backlog-validator-test-", "")
        tempDir.delete()
        tempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testValidBacklogPasses() {
        val validFile = File(tempDir, "issue-20260726-123456-valid.md")
        validFile.writeText("""
            ---
            title: "Valid Title"
            severity: "HIGH"
            status: "open"
            priority: high
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            target_files: ["some/file.kt"]
            ---

            # 🔴 [Severity: HIGH]: Valid Title
            **Context:** valid context
            **Needed:** valid needed
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertTrue(errors.isEmpty(), "Expected no errors for valid backlog, but got: $errors")
    }

    @Test
    fun testInvalidBacklogFlagsErrors() {
        val invalidFile = File(tempDir, "issue-20260726-02-invalid.md")
        invalidFile.writeText("""
            ---
            title: "Invalid Issue"
            severity: "UNKNOWN_SEVERITY"
            status: "invalid_status"
            priority: high
            dependencies: ["issue-non-existent"]
            ---

            # Invalid Issue
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty())
        assertTrue(errors.any { it.contains("Invalid severity") })
        assertTrue(errors.any { it.contains("Invalid status") })
        assertTrue(errors.any { it.contains("References non-existent dependency") })
    }

    @Test
    fun testInvalidPriorityLabelFailsParse() {
        val invalidFile = File(tempDir, "issue-20260726-02-bad-priority.md")
        invalidFile.writeText(
            """
            ---
            title: "Bad Priority"
            severity: "HIGH"
            status: "open"
            priority: urgent
            target_modules: [":enforcer"]
            target_files: ["x.kt"]
            ---

            # Bad
            """.trimIndent(),
        )
        val errors = BacklogValidator.validateBacklog(tempDir)
        assertTrue(errors.any { it.contains("Failed to parse") })
    }

    @Test
    fun testEmptyTargetModulesFailsValidation() {
        val file = File(tempDir, "issue-20260726-111111-empty-modules.md")
        file.writeText("""
            ---
            title: "Empty Target Modules"
            severity: "HIGH"
            status: "open"
            priority: high
            dependencies: []
            component: "enforcer"
            target_modules: []
            target_files: ["some/file.kt"]
            ---

            # 🔴 [Severity: HIGH]: Empty Target Modules
            **Context:** valid context
            **Needed:** valid needed
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty(), "Empty target_modules should fail validation")
        assertTrue(errors.any { it.contains("target_modules' must contain at least one valid Gradle module") }, "Expected empty target_modules error")
    }

    @Test
    fun testInvalidTargetModulesFailsValidation() {
        val file = File(tempDir, "issue-20260726-222222-invalid-modules.md")
        file.writeText("""
            ---
            title: "Invalid Target Modules"
            severity: "HIGH"
            status: "open"
            priority: high
            dependencies: []
            component: "enforcer"
            target_modules: [":invalid-module"]
            target_files: ["some/file.kt"]
            ---

            # 🔴 [Severity: HIGH]: Invalid Target Modules
            **Context:** valid context
            **Needed:** valid needed
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty(), "Invalid target_modules should fail validation")
        assertTrue(errors.any { it.contains("Invalid Gradle module ':invalid-module' in target_modules") }, "Expected invalid target_modules error")
    }

    @Test
    fun testInvalidFilenameFormat() {
        val file = File(tempDir, "issue-invalid.md")
        file.writeText("""
            ---
            title: "Valid Title"
            severity: "HIGH"
            status: "open"
            priority: high
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            target_files: ["some/file.kt"]
            ---
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty())
        assertTrue(errors.any { it.contains("Invalid filename format") })
    }

    @Test
    fun testMissingFrontmatterHeader() {
        val file = File(tempDir, "issue-20260726-123456-no-header.md")
        file.writeText("""
            title: "No Header"
            status: "open"
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty())
        assertTrue(errors.any { it.contains("Missing YAML frontmatter header") })
    }

    @Test
    fun testMissingTitle() {
        val file = File(tempDir, "issue-20260726-123456-missing-title.md")
        file.writeText("""
            ---
            title: ""
            severity: "HIGH"
            status: "open"
            priority: high
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            target_files: ["some/file.kt"]
            ---
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty())
        assertTrue(errors.any { it.contains("Missing or empty 'title'") })
    }

    @Test
    fun testInvalidComponent() {
        val file = File(tempDir, "issue-20260726-123456-invalid-comp.md")
        file.writeText("""
            ---
            title: "Invalid Comp"
            severity: "HIGH"
            status: "open"
            priority: high
            dependencies: []
            component: "invalid-component"
            target_modules: [":enforcer"]
            target_files: ["some/file.kt"]
            ---
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty())
        assertTrue(errors.any { it.contains("Invalid or missing component") })
    }

    @Test
    fun testMissingTargetFiles() {
        val file = File(tempDir, "issue-20260726-123456-missing-files.md")
        file.writeText("""
            ---
            title: "Missing Files"
            severity: "HIGH"
            status: "open"
            priority: high
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            ---
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty())
        assertTrue(errors.any { it.contains("Missing required 'target_files' field") })
    }

    @Test
    fun testMissingTargetModules() {
        val file = File(tempDir, "issue-20260726-123456-missing-modules.md")
        file.writeText("""
            ---
            title: "Missing Modules"
            severity: "HIGH"
            status: "open"
            priority: high
            dependencies: []
            component: "enforcer"
            target_files: ["some/file.kt"]
            ---
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty())
        assertTrue(errors.any { it.contains("Missing required 'target_modules' field") })
    }

    @Test
    fun testEmptyTargetFilesFailsValidation() {
        val file = File(tempDir, "issue-20260726-111111-empty-files.md")
        file.writeText("""
            ---
            title: "Empty Target Files"
            severity: "HIGH"
            status: "open"
            priority: high
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            target_files: []
            ---

            # 🔴 [Severity: HIGH]: Empty Target Files
            **Context:** valid context
            **Needed:** valid needed
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty(), "Empty target_files should fail validation")
        assertTrue(errors.any { it.contains("'target_files' must contain at least one file path") }, "Expected empty target_files error")
    }

    @Test
    fun testEmptyTargetFilesAllowedForDeferred() {
        val file = File(tempDir, "issue-20260726-111111-deferred-empty-files.md")
        file.writeText("""
            ---
            title: "Deferred Empty Target Files"
            severity: "HIGH"
            status: "deferred"
            priority: high
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            target_files: []
            ---

            # 🔴 [Severity: HIGH]: Deferred Empty Target Files
            **Context:** valid context
            **Needed:** valid needed
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertTrue(errors.isEmpty(), "Expected no errors for deferred backlog with empty target_files, but got: $errors")
    }

    @Test
    fun testResolvedStatusOutsideResolvedDirectoryFails() {
        val file = File(tempDir, "issue-20260726-111111-resolved-wrong-dir.md")
        file.writeText("""
            ---
            title: "Resolved In Wrong Dir"
            severity: "HIGH"
            status: "resolved"
            priority: high
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            target_files: ["some/file.kt"]
            ---

            # 🔴 [Severity: HIGH]: Resolved In Wrong Dir
            **Context:** valid context
            **Needed:** valid needed
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty(), "Resolved issue outside resolved dir should fail validation")
        assertTrue(errors.any { it.contains("Issue has status 'resolved' but is located in") })
    }

    @Test
    fun testNonResolvedStatusInsideResolvedDirectoryFails() {
        val resolvedDir = File(tempDir, "resolved").apply { mkdirs() }
        val file = File(resolvedDir, "issue-20260726-111111-open-in-resolved.md")
        file.writeText("""
            ---
            title: "Open In Resolved Dir"
            severity: "HIGH"
            status: "open"
            priority: high
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            target_files: ["some/file.kt"]
            ---

            # 🔴 [Severity: HIGH]: Open In Resolved Dir
            **Context:** valid context
            **Needed:** valid needed
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty(), "Open issue inside resolved dir should fail validation")
        assertTrue(errors.any { it.contains("Issue is in 'resolved' directory but has status 'open'") })
    }

    @Test
    fun testResolvedStatusInsideResolvedDirectoryPasses() {
        val resolvedDir = File(tempDir, "resolved").apply { mkdirs() }
        val file = File(resolvedDir, "issue-20260726-111111-valid-resolved.md")
        file.writeText("""
            ---
            title: "Valid Resolved Issue"
            severity: "HIGH"
            status: "resolved"
            priority: high
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            target_files: ["some/file.kt"]
            ---

            # 🔴 [Severity: HIGH]: Valid Resolved Issue
            **Context:** valid context
            **Needed:** valid needed
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertTrue(errors.isEmpty(), "Expected no errors for properly placed resolved issue, but got: $errors")
    }

    @Test
    fun testOpenQuestionsTrueWithoutSectionFailsValidation() {
        val file = File(tempDir, "issue-20260726-111111-open-questions-missing-section.md")
        file.writeText("""
            ---
            title: "Missing Questions Section"
            severity: "HIGH"
            status: "open"
            priority: high
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            target_files: ["some/file.kt"]
            open_questions: true
            ---

            # 🔴 [Severity: HIGH]: Missing Questions Section
            **Context:** valid context
            **Needed:** valid needed
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty(), "open_questions: true without section should fail validation")
        assertTrue(errors.any { it.contains("Declares 'open_questions: true' in frontmatter but is missing a non-empty '## ❓ Open Questions' section") })
    }

    @Test
    fun testOpenQuestionsSectionWithoutFrontmatterDeclarationFailsValidation() {
        val file = File(tempDir, "issue-20260726-111111-open-questions-missing-frontmatter.md")
        file.writeText("""
            ---
            title: "Missing Frontmatter Flag"
            severity: "HIGH"
            status: "open"
            priority: high
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            target_files: ["some/file.kt"]
            ---

            # 🔴 [Severity: HIGH]: Missing Frontmatter Flag
            **Context:** valid context
            **Needed:** valid needed

            ## ❓ Open Questions
            1. What is the expected behavior?
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty(), "Open Questions section without frontmatter flag should fail validation")
        assertTrue(errors.any { it.contains("Contains an 'Open Questions' section in body but frontmatter is missing 'open_questions: true'") })
    }

    @Test
    fun testValidOpenQuestionsPassesValidation() {
        val file = File(tempDir, "issue-20260726-111111-valid-open-questions.md")
        file.writeText("""
            ---
            title: "Valid Open Questions"
            severity: "HIGH"
            status: "open"
            priority: high
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            target_files: ["some/file.kt"]
            open_questions: true
            ---

            # 🔴 [Severity: HIGH]: Valid Open Questions
            **Context:** valid context
            **Needed:** valid needed

            ## ❓ Open Questions
            1. What is the expected behavior?
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertTrue(errors.isEmpty(), "Expected no errors for valid open_questions issue, but got: $errors")
    }

    @Test
    fun testNonExistentBacklogDir() {
        val nonExistent = File(tempDir, "non-existent")
        val errors = BacklogValidator.validateBacklog(nonExistent)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("Backlog directory does not exist"))
    }
}
