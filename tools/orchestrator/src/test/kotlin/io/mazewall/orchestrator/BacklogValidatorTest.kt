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
    fun testEmptyTargetModulesPassesValidationButExposesRisk() {
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
        // Currently, empty target_modules list passes validation because there is no non-empty check.
        // This test documents the gap that needs to be addressed in the documented issue.
        assertTrue(errors.isEmpty(), "Currently empty target_modules list passes validation")
    }
}
