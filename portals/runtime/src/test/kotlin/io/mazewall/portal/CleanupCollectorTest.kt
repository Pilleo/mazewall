package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
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

    @Test
    fun `continues after fatal cleanup and reports the fatal before ordinary failures`() {
        val attempted = mutableListOf<String>()
        val ordinary = IllegalStateException("ordinary")
        val fatal = AssertionError("fatal")
        val cleanup = CleanupCollector()

        cleanup.attempt("ordinary") {
            attempted += "ordinary"
            throw ordinary
        }
        cleanup.attempt("fatal") {
            attempted += "fatal"
            throw fatal
        }
        cleanup.attempt("later") { attempted += "later" }

        val failure = cleanup.outcome().failureOrNull()

        assertEquals(listOf("ordinary", "fatal", "later"), attempted)
        assertSame(fatal, failure)
        assertEquals(listOf(ordinary), fatal.suppressed.toList())
    }
}
