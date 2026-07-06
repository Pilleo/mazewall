package io.mazewall.ffi.networking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout

class NetworkOrderBufferTest {

    @Test
    fun `test writing values in network byte order`() {
        Arena.ofConfined().use { arena ->
            val segment = arena.allocate(32)
            val buffer = NetworkOrderBuffer(segment)

            // Unaligned writes should be used for arbitrary offsets
            buffer.writeIntUnaligned(0, 0x12345678)
            assertEquals(0x12.toByte(), segment.get(ValueLayout.JAVA_BYTE, 0))
            assertEquals(0x34.toByte(), segment.get(ValueLayout.JAVA_BYTE, 1))
            assertEquals(0x56.toByte(), segment.get(ValueLayout.JAVA_BYTE, 2))
            assertEquals(0x78.toByte(), segment.get(ValueLayout.JAVA_BYTE, 3))

            buffer.writeLongUnaligned(4, 0x1122334455667788L)
            assertEquals(0x11.toByte(), segment.get(ValueLayout.JAVA_BYTE, 4))
            assertEquals(0x22.toByte(), segment.get(ValueLayout.JAVA_BYTE, 5))
            assertEquals(0x88.toByte(), segment.get(ValueLayout.JAVA_BYTE, 11))

            buffer.writeByte(12, 0xAB.toByte())
            assertEquals(0xAB.toByte(), segment.get(ValueLayout.JAVA_BYTE, 12))

            // Aligned writes
            buffer.writeInt(16, 0x11223344)
            assertEquals(0x11.toByte(), segment.get(ValueLayout.JAVA_BYTE, 16))

            buffer.writeLong(24, 0x1122334455667788L)
            assertEquals(0x11.toByte(), segment.get(ValueLayout.JAVA_BYTE, 24))
        }
    }
}
