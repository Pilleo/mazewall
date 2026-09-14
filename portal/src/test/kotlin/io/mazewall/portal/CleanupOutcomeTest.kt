package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CleanupOutcomeTest {
    @Test
    fun `repeated observation does not duplicate suppressed failures`() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        val outcome = CleanupOutcome(listOf(CleanupFailure("one", first), CleanupFailure("two", second), CleanupFailure("alias", second)))

        repeat(2) { assertSame(first, outcome.failureOrNull()) }

        assertEquals(listOf(second), first.suppressed.toList())
    }

    @Test
    fun `fatal cleanup preserves its identity and ordinary evidence`() {
        val first = IllegalStateException("first")
        val fatal = LinkageError("fatal")
        val outcome = CleanupOutcome(listOf(CleanupFailure("one", first), CleanupFailure("two", fatal)))

        assertSame(fatal, outcome.failureOrNull())
        assertEquals(listOf(first), fatal.suppressed.toList())
    }
}
