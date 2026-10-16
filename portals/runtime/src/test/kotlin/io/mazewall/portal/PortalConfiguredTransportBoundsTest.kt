package io.mazewall.portal

import io.mazewall.platform.socket.RealSocketManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.test.assertFailsWith

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
}
