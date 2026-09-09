package io.mazewall.portal.worker

import io.mazewall.portal.Capability
import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalIncompleteFrameException
import io.mazewall.portal.PortalKind
import io.mazewall.portal.PortalMethod
import io.mazewall.portal.PortalPayload
import io.mazewall.portal.PortalReadTimeoutException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException

class PortalWorkerDriverTest {
    @Test
    fun `failed response write is terminal and never appends an error frame`() {
        val transport = FakeTransport(
            listOf(PortalWorkerReceive.Frame(request())),
            sendFailure = IOException("partial write"),
        )
        val driver = PortalWorkerDriver(transport) { _, _, _ -> byteArrayOf(1) }

        assertThrows(IOException::class.java) { driver.run() }

        assertEquals(1, transport.sendAttempts)
        assertEquals(PortalWorkerState.Stopped, driver.state)
    }

    @Test
    fun `idle timeout continues but partial frame timeout terminates`() {
        val transport = FakeTransport(
            listOf(PortalWorkerReceive.Idle, PortalWorkerReceive.Failure(PortalIncompleteFrameException())),
        )
        val driver = PortalWorkerDriver(transport) { _, _, _ -> ByteArray(0) }

        assertThrows(PortalIncompleteFrameException::class.java) { driver.run() }

        assertEquals(PortalWorkerState.Stopped, driver.state)
        assertEquals(2, transport.receiveAttempts)
    }

    @Test
    fun `reflective guest failure is unwrapped at the single dispatch boundary`() {
        val transport = FakeTransport(listOf(PortalWorkerReceive.Frame(request()), PortalWorkerReceive.PeerClosed))
        val driver = PortalWorkerDriver(transport) { _, _, _ ->
            throw InvocationTargetException(IllegalArgumentException("guest boom"))
        }

        driver.run()

        assertEquals(1, transport.sent.size)
        assertEquals(PortalKind.Error, transport.sent.single().kind)
        assertEquals(
            "guest boom",
            transport.sent
            .single()
            .payload
            .copyToByteArray()
            .decodeToString(),
        )
    }

    private fun request() = PortalFrame(PortalKind.Request, 7, PortalMethod.Echo, PortalPayload(ByteArray(0)), 0)

    private class FakeTransport(
        receives: List<PortalWorkerReceive>,
        private val sendFailure: IOException? = null,
    ) : PortalWorkerTransport {
        private val remaining = ArrayDeque(receives)
        val sent = mutableListOf<PortalFrame>()
        var sendAttempts = 0
        var receiveAttempts = 0

        override fun receive(): PortalWorkerReceive {
            receiveAttempts++
            return remaining.removeFirstOrNull() ?: PortalWorkerReceive.PeerClosed
        }

        override fun send(frame: PortalFrame) {
            sendAttempts++
            sendFailure?.let { throw it }
            sent += frame
        }

        override fun close() = Unit
    }
}
