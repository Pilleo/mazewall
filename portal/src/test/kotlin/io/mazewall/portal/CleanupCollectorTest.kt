package io.mazewall.portal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CleanupCollectorTest {
    @Test
    fun `records a failed cleanup and continues with remaining actions`() {
        val attempted = mutableListOf<String>()
        val cleanup = CleanupCollector()

        cleanup.attempt("channel") { attempted += "channel" }
        cleanup.attempt("socket") {
            attempted += "socket"
            throw IllegalStateException("socket close failed")
        }
        cleanup.attempt("process") { attempted += "process" }

        val outcome = cleanup.outcome()

        assertEquals(listOf("channel", "socket", "process"), attempted)
        assertFalse(outcome.complete)
        assertEquals("socket", outcome.failures.single().resource)
        assertTrue(outcome.failures.single().cause is IllegalStateException)
    }
}
