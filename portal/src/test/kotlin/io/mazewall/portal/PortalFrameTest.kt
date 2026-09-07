package io.mazewall.portal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PortalFrameTest {
    @Test
    fun `header round trip`() {
        val frame = PortalFrame(PortalKind.Request, 7, PortalMethod.Echo, byteArrayOf(1, 2, 3), 1)
        val parsed = PortalFrame.parseHeader(frame.headerBytes())
        assertEquals(PortalKind.Request, parsed.kind)
        assertEquals(7, parsed.requestId)
        assertEquals(PortalMethod.Echo, parsed.method)
        assertEquals(3, parsed.payloadLen)
        assertEquals(1, parsed.fdCount)
    }

    @Test
    fun `header parsing classifies builtin and generated method ids`() {
        val builtin = PortalFrame(PortalKind.Request, 7, PortalMethod.Echo, ByteArray(0), 0)
        val generated = PortalFrame(PortalKind.Request, 8, PortalMethod.Generated(99), ByteArray(0), 0)

        assertEquals(PortalMethod.Echo, PortalFrame.parseHeader(builtin.headerBytes()).method)
        assertEquals(PortalMethod.Generated(99), PortalFrame.parseHeader(generated.headerBytes()).method)
    }

    @Test
    fun `response cannot carry FDs`() {
        assertThrows(IllegalArgumentException::class.java) {
            PortalFrame(PortalKind.Response, 1, PortalMethod.Echo, ByteArray(0), 1)
        }
    }

    @Test
    fun `bad magic is rejected`() {
        val bytes = PortalFrame(PortalKind.Request, 1, PortalMethod.Echo, ByteArray(0), 0).headerBytes()
        bytes[0] = 'X'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) {
            PortalFrame.parseHeader(bytes)
        }
    }
}
