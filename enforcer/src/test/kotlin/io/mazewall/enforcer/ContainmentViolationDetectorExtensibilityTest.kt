package io.mazewall.enforcer

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContainmentViolationDetectorExtensibilityTest {

    @AfterEach
    fun teardown() {
        ContainmentViolationDetector.resetToDefaults()
    }

    @Test
    fun `can extend detector with custom matcher`() {
        val customException = IOException("Custom blocked phrase")

        // Initially should be false
        assertFalse(ContainmentViolationDetector.isContainmentViolation(customException))

        // Register custom matcher
        ContainmentViolationDetector.registerMatcher { t ->
            t.message?.contains("Custom blocked phrase") == true
        }

        // Now should be true
        assertTrue(ContainmentViolationDetector.isContainmentViolation(customException))
    }

    @Test
    fun `verify word boundary matching prevents false positives`() {
        // Backlog says: "Update DENIED_PHRASES matching to use a compiled Regex with \b boundaries"

        // This was a false positive in the old implementation
        val falsePositiveCandidate = IOException("SomePermission deniedly")

        // With \b word boundaries, this should now be FALSE
        assertFalse(ContainmentViolationDetector.isContainmentViolation(falsePositiveCandidate),
            "Refactored implementation should NOT have false positive for 'SomePermission deniedly'")

        // While the exact phrase should still be TRUE
        assertTrue(ContainmentViolationDetector.isContainmentViolation(IOException("Permission denied")))
    }
}
