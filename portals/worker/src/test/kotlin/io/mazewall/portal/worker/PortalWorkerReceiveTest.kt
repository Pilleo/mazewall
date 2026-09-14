package io.mazewall.portal.worker

import io.mazewall.portal.PortalIncompleteFrameException
import io.mazewall.portal.PortalPeerClosedException
import io.mazewall.portal.PortalReadTimeoutException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class PortalWorkerReceiveTest {
    @Test
    fun `peer EOF with a channel cleanup failure remains a failure`() {
        val closed = PortalPeerClosedException().apply { addSuppressed(IOException("channel close failed")) }
        val result = assertInstanceOf(PortalWorkerReceive.Failure::class.java, receiveWorkerFrame { throw closed })
        assertSame(closed, result.cause)
    }

    @Test
    fun `only clean EOF becomes peer closed and only pre-frame timeout is idle`() {
        assertEquals(PortalWorkerReceive.PeerClosed, receiveWorkerFrame { throw PortalPeerClosedException() })
        assertEquals(PortalWorkerReceive.Idle, receiveWorkerFrame { throw PortalReadTimeoutException() })
        for (failure in listOf(IOException("reset"), PortalIncompleteFrameException())) {
            val result = assertInstanceOf(PortalWorkerReceive.Failure::class.java, receiveWorkerFrame { throw failure })
            assertSame(failure, result.cause)
        }
        val fatal = AssertionError("native transport failed")
        assertSame(fatal, assertThrows(AssertionError::class.java) { receiveWorkerFrame { throw fatal } })
    }
}
