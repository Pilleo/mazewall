package io.mazewall.enforcer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ContainmentViolationExceptionTest {
    @Test
    fun `test ContainmentViolationException properties`() {
        val ex = ContainmentViolationException("Violated rules")
        assertEquals("Violated rules", ex.message)
        assertNull(ex.cause)

        val cause = RuntimeException("Root cause")
        val ex2 = ContainmentViolationException("Violated rules", cause)
        assertEquals(cause, ex2.cause)
    }
}
