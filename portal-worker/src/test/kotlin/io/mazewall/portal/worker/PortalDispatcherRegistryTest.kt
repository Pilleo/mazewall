package io.mazewall.portal.worker

import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.portal.PortalMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Registry contract for generated dispatchers: builtin ids (1..4) never reach it,
 * generated ids route to their registered handler, unknown ids yield null so the
 * worker can answer PortalKind.ERROR.
 */
class PortalDispatcherRegistryTest {
    /** Mimics the shape KotlinPoet emits for `<Service>PortalDispatcher`. */
    private object EchoServicePortalDispatcher {
        val METHOD_IDS: IntArray = intArrayOf(1000)

        fun handle(
            impl: EchoService,
            method: PortalMethod.Generated,
            payload: ByteArray,
            granted: List<FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership>>,
        ): ByteArray =
            when (method.wire) {
            1000 -> ((impl as EchoServiceImpl).tag + ":" + payload.decodeToString()).toByteArray()
            else -> error("unknown portal method ${method.wire}")
        }
    }

    interface EchoService

    private class EchoServiceImpl(
        tag: String,
    ) : EchoService {
        val tag: String = tag
        constructor() : this("impl")
    }

    private fun grantedFds(): List<FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership>> = emptyList()

    @Test
    fun `generated id routes through registry with bound impl`() {
        // Reflective bootstrap path, exactly as production configures it.
        // Explicit dispatcher object via ';' separator.
        val count = PortalDispatcherRegistry.bootstrapFromProperty(
            "${EchoService::class.java.name}=${EchoServiceImpl::class.java.name};" +
                EchoServicePortalDispatcher::class.java.name,
        )
        assertEquals(1, count)

        val out = PortalDispatcherRegistry.dispatchOrNull(
            PortalMethod.Generated(1000),
            "hello".toByteArray(),
            grantedFds(),
        )
        assertEquals("impl:hello", out?.decodeToString())
        assertNull(PortalDispatcherRegistry.dispatchOrNull(PortalMethod.Generated(1001), ByteArray(0), grantedFds()))
    }

    @Test
    fun `unknown generated ids are not claimed and builtin ids cannot be generated`() {
        assertNull(PortalDispatcherRegistry.dispatchOrNull(PortalMethod.Generated(9999), ByteArray(0), grantedFds()))
        assertFailsWith<IllegalArgumentException> { PortalMethod.Generated(1) }
    }

    @Test
    fun `malformed bootstrap entries are skipped without poisoning later ones`() {
        val count = PortalDispatcherRegistry.bootstrapFromProperty(
            "garbage-without-equals,${EchoService::class.java.name}=no.such.Impl," +
                "${EchoService::class.java.name}=${EchoServiceImpl::class.java.name};" +
                EchoServicePortalDispatcher::class.java.name,
        )
        assertEquals(1, count)
    }
}
