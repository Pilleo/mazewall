package io.mazewall.portal.worker

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import kotlin.test.Test
import kotlin.test.assertEquals
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
            methodId: Int,
            payload: ByteArray,
            granted: List<FileDescriptor<FileDescriptorRole.Granted, FdState.Open>>,
        ): ByteArray = when (methodId) {
            1000 -> ((impl as EchoServiceImpl).tag + ":" + payload.decodeToString()).toByteArray()
            else -> error("unknown portal method $methodId")
        }
    }

    interface EchoService

    private class EchoServiceImpl(tag: String) : EchoService {
        val tag: String = tag
        constructor() : this("impl")
    }

    private fun grantedFds(): List<FileDescriptor<FileDescriptorRole.Granted, FdState.Open>> = emptyList()

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
            1000,
            "hello".toByteArray(),
            grantedFds(),
        )
        assertEquals("impl:hello", out?.decodeToString())
        assertNull(PortalDispatcherRegistry.dispatchOrNull(1001, ByteArray(0), grantedFds()))
    }

    @Test
    fun `unknown and builtin ids are not claimed by the registry`() {
        assertNull(PortalDispatcherRegistry.dispatchOrNull(9999, ByteArray(0), grantedFds()))
        // Builtins stay owned by PortalBuiltinDispatch; registry ignores them.
        assertNull(PortalDispatcherRegistry.dispatchOrNull(1, ByteArray(0), grantedFds()))
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
