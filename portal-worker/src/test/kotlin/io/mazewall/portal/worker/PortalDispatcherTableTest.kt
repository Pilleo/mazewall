package io.mazewall.portal.worker

import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.fd.FileDescriptorRole
import io.mazewall.portal.Capability
import io.mazewall.portal.PortalMethod
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

public interface PublishedTableEchoService

public class PublishedTableEchoServiceImpl : PublishedTableEchoService

/** Same owner-list signature emitted by the generator; the guest closes its capability. */
public object ClosingCapabilityPortalDispatcher {
    public val METHOD_IDS: IntArray = intArrayOf(1101)

    public fun handle(
        impl: PublishedTableEchoService,
        method: PortalMethod.Generated,
        payload: ByteArray,
        granted: List<*>,
    ): ByteArray =
        (granted.single() as Capability.ReadFd).use { owner ->
            owner.borrow { fd ->
            io.mazewall.ffi.memory.NativeArena.ofConfined().use { arena ->
                val buffer = arena.allocate(1)
                check(
                    io.mazewall.platform.native.LinuxNative.memory
                    .read(fd, buffer, 1)
                    .getOrThrow("guest read") == 1L,
                )
                payload
            }
        }
        }
}

public object PublishedTableEchoServicePortalDispatcher {
    public val METHOD_IDS: IntArray = intArrayOf(1100)

    public fun handle(
        impl: PublishedTableEchoService,
        method: PortalMethod.Generated,
        payload: ByteArray,
        granted: List<*>,
    ): ByteArray = payload
}

class PortalDispatcherTableTest {
    private val granted = emptyList<Capability.ReadFd>()

    @Test
    fun `reflective generated handler and final cleanup share the capability owner`() {
        val path = java.nio.file.Files
            .createTempFile("portal-owner", ".txt")
        java.nio.file.Files
            .writeString(path, "x")
        val owner = io.mazewall.portal
            .ProcessBroker()
            .openReadOnly(path.parent, path.fileName.toString())
        try {
            val builder = PortalDispatcherTable.Builder()
            builder.bootstrapStrict("${PublishedTableEchoService::class.java.name}=${PublishedTableEchoServiceImpl::class.java.name};${ClosingCapabilityPortalDispatcher::class.java.name}")
            val payload = byteArrayOf(7)
            assertSame(payload, builder.build().dispatchOrNull(PortalMethod.Generated(1101), payload, listOf(owner)))
            Capability.closeAll(listOf(owner))
            assertFailsWith<IllegalStateException> { owner.borrow { error("closed owner reached native code") } }
        } finally {
            owner.close()
            java.nio.file.Files
                .delete(path)
        }
    }

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
        assertFailsWith<IllegalStateException> { builder.bootstrapFromProperty("malformed") }
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
    fun `production publication seals compatibility registration`() {
        val dispatchers = PortalWorkerMain.publishDispatcherTable(
            "${PublishedTableEchoService::class.java.name}=" +
                "${PublishedTableEchoServiceImpl::class.java.name};" +
                PublishedTableEchoServicePortalDispatcher::class.java.name,
        )
        val payload = byteArrayOf(11)

        assertSame(
            payload,
            dispatchers.table.dispatchOrNull(PortalMethod.Generated(1100), payload, granted),
        )
        assertSame(
            payload,
            PortalDispatcherRegistry.dispatchOrNull(PortalMethod.Generated(1100), payload, granted),
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
