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
            priority: 9
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            target_files: []
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
            priority: 99
            dependencies: ["issue-non-existent"]
            ---

            # Invalid Issue
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty())
        assertTrue(errors.any { it.contains("Invalid severity") })
        assertTrue(errors.any { it.contains("Invalid status") })
        assertTrue(errors.any { it.contains("Priority '99' out of bounds") })
        assertTrue(errors.any { it.contains("References non-existent dependency") })
    }

    @Test
    fun testEmptyTargetModulesFailsValidation() {
        val file = File(tempDir, "issue-20260726-111111-empty-modules.md")
        file.writeText("""
            ---
            title: "Empty Target Modules"
            severity: "HIGH"
            status: "open"
            priority: 9
            dependencies: []
            component: "enforcer"
            target_modules: []
            target_files: []
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
            priority: 9
            dependencies: []
            component: "enforcer"
            target_modules: [":invalid-module"]
            target_files: []
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
            priority: 9
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            target_files: []
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
            priority: 9
            dependencies: []
            component: "enforcer"
            target_modules: [":enforcer"]
            target_files: []
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
            priority: 9
            dependencies: []
            component: "invalid-component"
            target_modules: [":enforcer"]
            target_files: []
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
            priority: 9
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
            priority: 9
            dependencies: []
            component: "enforcer"
            target_files: []
            ---
        """.trimIndent())

        val errors = BacklogValidator.validateBacklog(tempDir)
        assertFalse(errors.isEmpty())
        assertTrue(errors.any { it.contains("Missing required 'target_modules' field") })
    }

    @Test
    fun testNonExistentBacklogDir() {
        val nonExistent = File(tempDir, "non-existent")
        val errors = BacklogValidator.validateBacklog(nonExistent)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("Backlog directory does not exist"))
    }
}
