package io.mazewall.portal.worker

import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.portal.Capability
import io.mazewall.portal.PortalMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Startup bootstrap contract for generated dispatchers: malformed strict entries fail,
 * tolerant startup skips malformed entries, and valid entries publish into a table.
 */
class PortalDispatcherRegistryTest {
    /** Mimics the shape KotlinPoet emits for `<Service>PortalDispatcher`. */
    private object EchoServicePortalDispatcher {
        val METHOD_IDS: IntArray = intArrayOf(1000)

        fun handle(
            impl: EchoService,
            method: PortalMethod.Generated,
            payload: ByteArray,
            granted: List<Capability.ReadFd>,
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

    private fun grantedFds(): List<Capability.ReadFd> = emptyList()

    @Test
    fun `strict bootstrap rejects malformed entries before a valid dispatcher is published`() {
        val builder = PortalDispatcherTable.Builder()
        assertFailsWith<IllegalArgumentException> { builder.bootstrapStrict("malformed") }

        val count = builder.bootstrapStrict(
            "${EchoService::class.java.name}=${EchoServiceImpl::class.java.name};" +
                EchoServicePortalDispatcher::class.java.name,
        )
        assertEquals(1, count)
        val table = builder.build()

        val out = table.dispatchOrNull(
            PortalMethod.Generated(1000),
            "hello".toByteArray(),
            grantedFds(),
        )
        assertEquals("impl:hello", out?.decodeToString())
        assertNull(table.dispatchOrNull(PortalMethod.Generated(1001), ByteArray(0), grantedFds()))
    }

    @Test
    fun `unknown generated ids are not claimed and builtin ids cannot be generated`() {
        assertFailsWith<IllegalArgumentException> { PortalMethod.Generated(1) }
    }

    @Test
    fun `malformed bootstrap entries are skipped without poisoning later ones`() {
        val builder = PortalDispatcherTable.Builder()
        val count = builder.bootstrapFromProperty(
            "garbage-without-equals,${EchoService::class.java.name}=no.such.Impl," +
                "${EchoService::class.java.name}=${EchoServiceImpl::class.java.name};" +
                EchoServicePortalDispatcher::class.java.name,
        )
        assertEquals(1, count)
        assertEquals(
            "impl:hello",
            builder
                .build()
                .dispatchOrNull(PortalMethod.Generated(1000), "hello".toByteArray(), grantedFds())
                ?.decodeToString(),
        )
    }
}
