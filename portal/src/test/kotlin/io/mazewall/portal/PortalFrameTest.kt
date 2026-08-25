package io.mazewall.portal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PortalFrameTest {
    @Test
    fun `header round trip`() {
        val frame = PortalFrame(PortalKind.REQUEST, 7, PortalMethods.ECHO, byteArrayOf(1, 2, 3), 1)
        val parsed = PortalFrame.parseHeader(frame.headerBytes())
        assertEquals(PortalKind.REQUEST, parsed.kind)
        assertEquals(7, parsed.requestId)
        assertEquals(PortalMethods.ECHO, parsed.methodId)
        assertEquals(3, parsed.payloadLen)
        assertEquals(1, parsed.fdCount)
    }

    @Test
    fun `response cannot carry FDs`() {
        assertThrows(IllegalArgumentException::class.java) {
            PortalFrame(PortalKind.RESPONSE, 1, PortalMethods.ECHO, ByteArray(0), 1)
        }
    }

    @Test
    fun `bad magic is rejected`() {
        val bytes = PortalFrame(PortalKind.REQUEST, 1, 1, ByteArray(0), 0).headerBytes()
        bytes[0] = 'X'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) {
            PortalFrame.parseHeader(bytes)
        }
    }
}
