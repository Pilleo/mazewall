package io.mazewall.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CleanupFailuresTest {
    @Test
    fun `failure fold preserves identities and deduplicates aliases`() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        assertSame(first, CleanupFailures.merge(null, first))
        assertSame(first, CleanupFailures.merge(first, first))
        repeat(2) { assertSame(first, CleanupFailures.merge(first, second)) }
        assertEquals(listOf(second), first.suppressed.toList())
    }

    @Test
    fun `fatal errors take precedence and first fatal identity wins`() {
        val ordinary = IllegalStateException("ordinary")
        val fatal = LinkageError("fatal")
        val laterFatal = AssertionError("later")
        assertSame(fatal, CleanupFailures.merge(ordinary, fatal))
        assertSame(fatal, CleanupFailures.merge(fatal, laterFatal))
        assertEquals(listOf(ordinary, laterFatal), fatal.suppressed.toList())
    }

    @Test
    fun `fold does not alter caller interruption policy`() {
        Thread.interrupted()
        try {
            CleanupFailures.merge(null, InterruptedException("cleanup"))
            assertFalse(Thread.currentThread().isInterrupted)
            Thread.currentThread().interrupt()
            CleanupFailures.merge(null, IllegalStateException("cleanup"))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
