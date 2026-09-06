package io.mazewall.portal.worker

import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.portal.Capability
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
            methodId: Int,
            payload: ByteArray,
            granted: List<FileDescriptor<FileDescriptorRole.Granted, FdState.Open>>,
        ): ByteArray = when (methodId) {
            1000 -> ((impl as EchoServiceImpl).tag + ":" + payload.decodeToString()).toByteArray()
            else -> error("unknown portal method $methodId")
        }
    }

    /** Represents the descriptor shape emitted by the real generated dispatcher. */
    private object CapabilityServicePortalDispatcher {
        val METHOD_IDS: IntArray = intArrayOf(1002)

        fun handle(
            impl: EchoService,
            methodId: Int,
            payload: ByteArray,
            granted: List<Capability.ReadFd>,
        ): ByteArray =
            when (methodId) {
                1002 -> {
                    assertEquals("ReadFd", granted.single()::class.simpleName)
                    (impl as EchoServiceImpl).tag.toByteArray()
                }
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
    fun `registry adapts received descriptors to generated capability parameters`() {
        val count = PortalDispatcherRegistry.bootstrapFromProperty(
            "${EchoService::class.java.name}=${EchoServiceImpl::class.java.name};" +
                CapabilityServicePortalDispatcher::class.java.name,
        )
        assertEquals(1, count)

        val granted = FileDescriptor.adopt(1234, FileDescriptorRole.Granted)
        val out = PortalDispatcherRegistry.dispatchOrNull(1002, ByteArray(0), listOf(granted))
        assertEquals("impl", out?.decodeToString())
    }

    @Test
    fun `unknown builtin method signals generated-dispatch fallback`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                PortalBuiltinDispatch.handle(1003, ByteArray(0), emptyList())
            }
        assertEquals("unknown method 1003", failure.message)
    }

    @Test
    fun `malformed bootstrap entry fails closed`() {
        assertFailsWith<IllegalArgumentException> {
            PortalDispatcherRegistry.bootstrapFromProperty("garbage-without-equals")
        }
    }
}
