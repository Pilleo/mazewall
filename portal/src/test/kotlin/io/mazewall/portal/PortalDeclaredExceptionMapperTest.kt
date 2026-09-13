package io.mazewall.portal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PortalDeclaredExceptionMapperTest {
    @Test
    fun `maps an explicitly declared message exception`() {
        val thrown =
            assertThrows(InvalidDocument::class.java) {
                PortalDeclaredExceptionMapper.rethrow(
                    PortalRemoteCallException(InvalidDocument::class.java.name, "bad header"),
                    arrayOf(InvalidDocument::class.java),
                )
            }

        assertEquals("bad header", thrown.message)
    }

    @Test
    fun `refuses an undeclared remote type`() {
        val remote = PortalRemoteCallException(IllegalArgumentException::class.java.name, "untrusted")

        val thrown =
            assertThrows(PortalRemoteCallException::class.java) {
                PortalDeclaredExceptionMapper.rethrow(remote, arrayOf(InvalidDocument::class.java))
            }

        assertSame(remote, thrown)
    }

    class InvalidDocument(
        message: String,
    ) : Exception(message)
}
