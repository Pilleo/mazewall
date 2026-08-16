package io.mazewall.enforcer

import io.mazewall.Policy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import io.mazewall.enforcer.diagnostics.ViolationMatcher
import io.mazewall.enforcer.api.ContainmentViolationException

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
    fun `can extend detector with custom phrase`() {
        val customException = IOException("This action was blocked by sandbox engine")

        // Initially should be false
        assertFalse(ContainmentViolationDetector.isContainmentViolation(customException))

        // Register custom phrase
        ContainmentViolationDetector.registerPhrase("blocked by sandbox engine")

        // Now should be true
        assertTrue(ContainmentViolationDetector.isContainmentViolation(customException))

        // Verify word boundaries
        assertFalse(ContainmentViolationDetector.isContainmentViolation(IOException("This action was blocked by sandbox enginely")))
    }

    @Test
    fun `can extend detector with custom regex`() {
        val customException = IOException("Access restricted: error 99")

        // Initially should be false
        assertFalse(ContainmentViolationDetector.isContainmentViolation(customException))

        // Register custom regex
        ContainmentViolationDetector.registerRegex(Regex("""Access restricted: error \d+"""))

        // Now should be true
        assertTrue(ContainmentViolationDetector.isContainmentViolation(customException))
    }

    @Test
    fun `custom phrases and regexes are found in findViolationRanges`() {
        ContainmentViolationDetector.registerPhrase("blocked by sandbox")
        ContainmentViolationDetector.registerRegex(Regex("""restricted: \d+"""))

        val msg = "Operation blocked by sandbox on path /tmp/foo, restricted: 42"
        val ranges = ContainmentViolationDetector.findViolationRanges(msg).toList()

        assertEquals(2, ranges.size)
        assertEquals("blocked by sandbox", msg.substring(ranges[0]))
        assertEquals("restricted: 42", msg.substring(ranges[1]))
    }

    @Test
    fun `can configure custom phrases and regexes on independent detector`() {
        val detector = ContainmentViolationDetector(
            initialCustomPhrases = listOf("blocked by sandbox"),
            initialCustomRegexes = listOf(Regex("""restricted: \d+""")),
            useDefaults = false,
            loadServices = false
        )

        val customException1 = IOException("blocked by sandbox")
        val customException2 = IOException("restricted: 123")
        val defaultException = IOException("Permission denied")

        assertTrue(detector.isContainmentViolation(customException1))
        assertTrue(detector.isContainmentViolation(customException2))
        assertFalse(detector.isContainmentViolation(defaultException))
    }

    @Test
    fun `can configure custom phrases and regexes via Policy and PolicyBuilder`() {
        val policy = Policy.builder()
            .customViolationPhrase("blocked by sandbox")
            .customViolationRegex(Regex("""restricted: \d+"""))
            .build()

        assertTrue(policy.customViolationPhrases.contains("blocked by sandbox"))
        assertTrue(policy.customViolationRegexes.any { it.pattern == """restricted: \d+""" })
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

    @Test
    fun `verify consolidated regex matches various violation phrases`() {
        // Phrases from DENIED_PHRASES
        assertTrue(ContainmentViolationDetector.isContainmentViolation(IOException("Operation not permitted")))
        assertTrue(ContainmentViolationDetector.isContainmentViolation(IOException("refusé")))
        assertTrue(ContainmentViolationDetector.isContainmentViolation(IOException("verweigert")))
        assertTrue(ContainmentViolationDetector.isContainmentViolation(IOException("negado")))

        // "Cannot run" phrase
        assertTrue(ContainmentViolationDetector.isContainmentViolation(IOException("Cannot run program \"ls\": error=13, Permission denied")))
        assertTrue(ContainmentViolationDetector.isContainmentViolation(IOException("Cannot run program \"foo\"")))
    }

    @Test
    fun `verify findViolationRanges skips generic prefixes but matches reasons`() {
        val msg = "Cannot run program \"ls\": error=13, Permission denied"
        val ranges = ContainmentViolationDetector.findViolationRanges(msg).toList()

        // Should NOT match "Cannot run"
        // Should match "Permission denied"
        assertTrue(ranges.isNotEmpty(), "Should match at least one phrase")
        val matchedText = msg.substring(ranges.first())
        assertTrue(matchedText.contains("Permission denied", ignoreCase = true), "Should match 'Permission denied', got '$matchedText'")
        assertFalse(matchedText.contains("Cannot run", ignoreCase = true), "Should NOT match 'Cannot run'")
    }

    @Test
    fun `default detector automatically loads violation matchers via ServiceLoader`() {
        val spiException = IOException("This is a SPI_VIOLATION_TRIGGER_KEYWORD error!")

        // This should be true because of TestServiceViolationMatcher registered via META-INF/services
        assertTrue(ContainmentViolationDetector.isContainmentViolation(spiException))

        // Unrelated exception should still be false
        assertFalse(ContainmentViolationDetector.isContainmentViolation(IOException("Unrelated message")))
    }

    @Test
    fun `can instantiate independent detector class with custom configuration`() {
        // Create an instance that does NOT use default matchers and has a specific custom matcher
        val customDetector = ContainmentViolationDetector(
            customMatchers = listOf(ViolationMatcher { t -> t.message?.contains("SPECIFIC_TEST_KEYWORD") == true }),
            useDefaults = false,
            loadServices = false // Disables service loader for this instance
        )

        val customException = IOException("contains SPECIFIC_TEST_KEYWORD")
        val defaultException = IOException("Permission denied")

        // Custom detector matches custom keyword but NOT standard defaults
        assertTrue(customDetector.isContainmentViolation(customException))
        assertFalse(customDetector.isContainmentViolation(defaultException))

        // Default detector matches standard defaults but NOT custom keyword
        assertTrue(ContainmentViolationDetector.isContainmentViolation(defaultException))
        assertFalse(ContainmentViolationDetector.isContainmentViolation(customException))
    }
}
