package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContainmentViolationExceptionTest {
    @Test
    fun `thrown API exception is caught as historical package type`() {
        val thrown =
            io.mazewall.enforcer.api.ContainmentViolationException(
                "contained",
                IllegalStateException("cause"),
            )
        val caught = assertFailsWith<ContainmentViolationException> {
            throw thrown
        }
        assertEquals("contained", caught.message)
        assertIs<IllegalStateException>(caught.cause)
        assertTrue(caught is io.mazewall.enforcer.api.ContainmentViolationException)
    }

    @Test
    fun `historical constructors still exist`() {
        val ex = ContainmentViolationException("old", null)
        assertEquals("old", ex.message)
    }
}
