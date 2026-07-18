package io.mazewall.ffi.memory

import org.junit.jupiter.api.Test
import java.lang.foreign.ValueLayout
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryWrappersTest {
    @Test
    fun `test SockFilterSegment getters and setters`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val segment = SockFilterSegment.allocate()
                segment.setCode(0x1234.toShort())
                segment.setJt(0x56.toByte())
                segment.setJf(0x78.toByte())
                segment.setK(0x9abcdef0.toInt())

                assertEquals(0x1234.toShort(), segment.getCode())
                assertEquals(0x56.toByte(), segment.getJt())
                assertEquals(0x78.toByte(), segment.getJf())
                assertEquals(0x9abcdef0.toInt(), segment.getK())
            }
        }
    }

    @Test
    fun `test PollFdSegment getters and setters`() {
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                val segment = PollFdSegment.allocate()
                segment.setFd(42)
                segment.setEvents(0x1234.toShort())
                segment.setRevents(0x5678.toShort())

                assertEquals(42, segment.getFd())
                assertEquals(0x1234.toShort(), segment.getEvents())
                assertEquals(0x5678.toShort(), segment.getRevents())
            }
        }
    }

    @Test
    fun `test memory segment extension methods`() {
        NativeArena.ofConfined().use { arena ->
            val segment = arena.allocate(100)

            segment.writeByte(0, 0x12.toByte())
            assertEquals(0x12.toByte(), segment.readByte(0))

            segment.writeShort(2, 0x1234.toShort())
            assertEquals(0x1234.toShort(), segment.readShort(2))

            segment.writeInt(4, 0x12345678)
            assertEquals(0x12345678, segment.readInt(4))

            segment.writeIntUnaligned(9, 0x12345678)
            assertEquals(0x12345678, segment.readIntUnaligned(9))

            segment.writeLong(16, 0x1234567890abcdefL)
            assertEquals(0x1234567890abcdefL, segment.readLong(16))

            segment.writeLongUnaligned(25, 0x1234567890abcdefL)
            assertEquals(0x1234567890abcdefL, segment.readLongUnaligned(25))
        }
    }

    @Test
    fun `test getSystemStrerror`() {
        val error = getSystemStrerror(1) // EPERM
        assertTrue(error != null && error.isNotEmpty())
    }
}
