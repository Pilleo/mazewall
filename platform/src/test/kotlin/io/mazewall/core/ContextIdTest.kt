package io.mazewall.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ContextIdTest {

    @Test
    fun `unknown sentinel is zero`() {
        assertEquals(0u, ContextId.UNKNOWN.value)
        assertTrue(ContextId.UNKNOWN.isUnknown)
    }

    @Test
    fun `nonzero values are never unknown`() {
        assertFalse(ContextId(1u).isUnknown)
        assertFalse(ContextId(UInt.MAX_VALUE).isUnknown)
    }

    @Test
    fun `wire size is exactly four bytes`() {
        assertEquals(4, ContextId.WIRE_SIZE_BYTES)
        assertEquals(4, ContextId.UNKNOWN.encode().size)
    }

    @Test
    fun `encoding is big-endian`() {
        val bytes = ContextId(0x01020304u).encode()
        byteArrayOf(0x01, 0x02, 0x03, 0x04).forEachIndexed { i, expected ->
            assertEquals(expected, bytes[i], "byte $i")
        }
    }

    @Test
    fun `round trip survives boundary values`() {
        val boundaries = listOf(0u, 1u, 0x7FFFFFFFu, 0x80000000u, 0xFEEDC0DEu, 0xFFFFFFFFu)
        for (v in boundaries) {
            val decoded = ContextId.decodeFrom(ContextId(v).encode())
            assertEquals(v, decoded.value, "boundary 0x${v.toString(16)}")
        }
    }

    @Test
    fun `decoding zeroed wire bytes yields unknown`() {
        val decoded = ContextId.decodeFrom(byteArrayOf(0, 0, 0, 0))
        assertEquals(ContextId.UNKNOWN, decoded)
        assertTrue(decoded.isUnknown)
    }

    @Test
    fun `encodeInto respects offset without touching neighbors`() {
        val dst = ByteArray(6) { 0x55 }
        ContextId(0xAABBCCDDu).encodeInto(dst, offset = 1)
        assertEquals(0x55.toByte(), dst[0])
        assertEquals(0xAA.toByte(), dst[1])
        assertEquals(0xBB.toByte(), dst[2])
        assertEquals(0xCC.toByte(), dst[3])
        assertEquals(0xDD.toByte(), dst[4])
        assertEquals(0x55.toByte(), dst[5])
    }

    @Test
    fun `decodeFrom reads at offset`() {
        val src = byteArrayOf(0x7F, 0x00, 0x00, 0x42, 0x7F)
        assertEquals(0x0000427Fu, ContextId.decodeFrom(src, offset = 1).value)
    }

    @Test
    fun `short buffers are rejected on encode and decode`() {
        assertThrows(IllegalArgumentException::class.java) {
            ContextId(1u).encodeInto(ByteArray(3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContextId.decodeFrom(ByteArray(3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContextId.decodeFrom(ByteArray(8), offset = 5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContextId(1u).encodeInto(ByteArray(8), offset = -1)
        }
    }

    @Test
    fun `value class equality is structural`() {
        assertEquals(ContextId(9u), ContextId(9u))
        assertNotEquals(ContextId(9u), ContextId(10u))
        assertNotEquals(ContextId(9u), ContextId.UNKNOWN)
        assertEquals("context(9)", ContextId(9u).toString())
    }
}
