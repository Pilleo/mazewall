package io.mazewall.portal

import io.mazewall.platform.socket.RealSocketManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PortalConfiguredTransportBoundsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `oversized configured response rejects its header before reading a body`() {
        val path = directory.resolve("bounded.sock").toString()
        val server = RealSocketManager.createUnixServer(path)
        try {
            SocketChannel.open(UnixDomainSocketAddress.of(path)).use { peer ->
                PortalChannel(RealSocketManager.accept(server), receivePayloadLimit = 4).use { channel ->
                    val frame = PortalFrame(PortalKind.Response, 1, PortalMethod.Echo, ByteArray(5), 0)
                    peer.write(ByteBuffer.wrap(frame.headerBytes()))
                    assertFailsWith<IllegalArgumentException> { channel.receive(1_000L) }
                }
            }
        } finally {
            RealSocketManager.close(server)
        }
    }

    @Test
    fun `broker request writer and scoped reply limit enforce configured byte bounds before allocation`() {
        ProcessBroker(limits = PortalRuntimeLimits(maxRequestBytes = 32)).use { broker ->
            val ok = broker.newRequestWriter()
            ok.string("a".repeat(28)) // 4 len + 28 data = 32 bytes exact
            assertEquals(32, ok.toByteArray().size)

            val oversized = broker.newRequestWriter()
            val failure = assertFailsWith<PortalCallException> { oversized.string("a".repeat(29)) }
            assertEquals(PortalInvocationEvidence.NOT_STARTED, failure.evidence)
            assertEquals(0, oversized.growthCount)
            assertEquals(0L, oversized.peakAllocatedBytes)
        }

        PortalRequestWriter.withReplyLimit(32) {
            val reply = PortalRequestWriter.forReply()
            assertEquals(32, reply.maxBytes)
            assertFailsWith<PortalCallException> { reply.bytes(ByteArray(29)) }
            assertEquals(0L, reply.peakAllocatedBytes)
        }
        assertEquals(PortalFrame.MAX_PAYLOAD, PortalRequestWriter.forReply().maxBytes)
        assertFailsWith<IllegalArgumentException> { PortalRequestWriter.withReplyLimit(PortalFrame.MAX_PAYLOAD + 1) {} }
        assertTrue(
            runCatching { PortalRequestWriter.withReplyLimit(16) { error("cleanup") } }.isFailure &&
                PortalRequestWriter.forReply().maxBytes == PortalFrame.MAX_PAYLOAD,
        )
    }
}
