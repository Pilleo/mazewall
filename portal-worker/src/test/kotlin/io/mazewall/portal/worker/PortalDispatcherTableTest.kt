package io.mazewall.portal.worker

import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.portal.PortalMethod
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

public interface PublishedTableEchoService

public class PublishedTableEchoServiceImpl : PublishedTableEchoService

public object PublishedTableEchoServicePortalDispatcher {
    public val METHOD_IDS: IntArray = intArrayOf(1100)

    public fun handle(
        impl: PublishedTableEchoService,
        method: PortalMethod.Generated,
        payload: ByteArray,
        granted: List<FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership>>,
    ): ByteArray = payload
}

class PortalDispatcherTableTest {
    private val granted = emptyList<FileDescriptor<FileDescriptorRole.Granted, FdState.Open, FdOwnership>>()

    @Test
    fun `build publishes a stable dispatch table and invalidates its builder`() {
        val firstResult = byteArrayOf(7, 8, 9)
        val first: PortalDispatcherHandler = { _, _, _ -> firstResult }
        val second: PortalDispatcherHandler = { _, _, _ -> byteArrayOf(10) }
        val builder = PortalDispatcherTable.Builder()

        val table = builder.register(intArrayOf(1000), first).build()

        assertSame(
            firstResult,
            table.dispatchOrNull(PortalMethod.Generated(1000), byteArrayOf(1), granted),
        )
        assertNull(table.dispatchOrNull(PortalMethod.Generated(1001), ByteArray(0), granted))
        assertFailsWith<IllegalStateException> {
            builder.register(intArrayOf(1001), second)
        }
        assertFailsWith<IllegalStateException> { builder.build() }
    }

    @Test
    fun `duplicate method ids are rejected without replacing the first handler`() {
        val firstResult = byteArrayOf(1)
        val first: PortalDispatcherHandler = { _, _, _ -> firstResult }
        val second: PortalDispatcherHandler = { _, _, _ -> byteArrayOf(2) }
        val builder = PortalDispatcherTable.Builder().register(intArrayOf(1000), first)

        assertFailsWith<IllegalStateException> {
            builder.register(intArrayOf(1000), second)
        }

        assertSame(
            firstResult,
            builder.build().dispatchOrNull(PortalMethod.Generated(1000), ByteArray(0), granted),
        )
    }

    @Test
    fun `compatibility bootstrap cannot mutate after publication`() {
        val registeredResult = byteArrayOf(11)
        PortalDispatcherRegistry.register(intArrayOf(1100)) { _, _, _ -> registeredResult }
        PortalDispatcherRegistry.freeze()

        assertSame(
            registeredResult,
            PortalDispatcherRegistry.dispatchOrNull(PortalMethod.Generated(1100), ByteArray(0), granted),
        )

        assertFailsWith<IllegalStateException> {
            PortalDispatcherRegistry.register(intArrayOf(1101)) { _, _, _ -> ByteArray(0) }
        }
        assertFailsWith<IllegalStateException> { PortalDispatcherRegistry.bootstrapStrict(null) }
        assertFailsWith<IllegalStateException> {
            PortalDispatcherRegistry.bootstrapFromProperty(
                "${PublishedTableEchoService::class.java.name}=" +
                    "${PublishedTableEchoServiceImpl::class.java.name};" +
                    PublishedTableEchoServicePortalDispatcher::class.java.name,
                )
        }
    }
}
