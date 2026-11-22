package io.mazewall.portal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `falls back to no-arg constructor only when message constructor is absent`() {
        val thrown =
            assertThrows(NoMessageException::class.java) {
                PortalDeclaredExceptionMapper.rethrow(
                    PortalRemoteCallException(NoMessageException::class.java.name, "ignored"),
                    arrayOf(NoMessageException::class.java),
                )
            }

        assertEquals("default-ctor", thrown.message)
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

    @Test
    fun `fatal constructor failure unwraps exact Error without invoking fallback constructor`() {
        ExplodingMessageException.noArgCalled = false
        val remote = PortalRemoteCallException(ExplodingMessageException::class.java.name, "boom")
        val gate = PortalStatefulInvocationGate()
        val manifest =
            PortalServiceManifest(
                serviceId = PortalServiceId("svc"),
                contractName = "fixture.Svc",
                implementationName = "fixture.SvcImpl",
                dispatcherName = "fixture.SvcPortalDispatcher",
                schemaId = "schema",
                isolationGroup = "group",
                instanceMode = PortalInstanceMode.STATEFUL_SINGLETON,
                policyProfile = "restricted",
                workerArtifact = "worker",
            )

        val actual =
            assertThrows(AssertionError::class.java) {
                gate.invoke(manifest) {
                    PortalDeclaredExceptionMapper.rethrow(remote, arrayOf(ExplodingMessageException::class.java))
                }
            }

        assertSame(ExplodingMessageException.FATAL, actual)
        assertFalse(ExplodingMessageException.noArgCalled)
        assertTrue(remote in actual.suppressed)
        assertEquals(PortalServiceHealthState.STATE_LOST, gate.health(manifest).state)
    }

    class InvalidDocument(
        message: String,
    ) : Exception(message)

    class NoMessageException : Exception("default-ctor")

    class ExplodingMessageException : Exception {
        @Suppress("unused") // Reflection test seam proving fallback constructor is skipped.
        constructor() : super("fallback") {
            noArgCalled = true
        }

        @Suppress("unused", "UNUSED_PARAMETER") // Invoked reflectively by PortalDeclaredExceptionMapper.
        constructor(message: String) : super(message) {
            throw FATAL
        }

        companion object {
            val FATAL = AssertionError("declared exception ctor fatal")
            var noArgCalled = false
        }
    }
}
