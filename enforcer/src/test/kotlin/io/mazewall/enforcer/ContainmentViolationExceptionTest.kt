package io.mazewall.enforcer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

@Suppress("DEPRECATION")
class ContainmentViolationExceptionTest {
    @Test
    fun `thrown API exception is caught as historical package type`() {
        val thrown =
            io.mazewall.enforcer.api.ContainmentViolationException(
                "contained",
                IllegalStateException("cause"),
            )
        try {
            throw thrown
        } catch (caught: ContainmentViolationException) {
            assertEquals("contained", caught.message)
            assertIs<IllegalStateException>(caught.cause)
            assertTrue(caught is io.mazewall.enforcer.api.ContainmentViolationException)
            return
        }
        fail("historical catch type must match library throws of the API exception")
    }

    @Test
    fun `historical constructors still exist`() {
        val ex = ContainmentViolationException("old", null)
        assertEquals("old", ex.message)
    }
}
