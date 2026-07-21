package io.mazewall.ffi.memory

import org.junit.jupiter.api.Test
import java.lang.foreign.ValueLayout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeArenaTest {
    @Test
    fun `test NativeArena ofConfined lifecycle`() {
        val arena = NativeArena.ofConfined()
        assertTrue(arena.isAlive)

        val segment = arena.allocate(16)
        assertEquals(16L, segment.byteSize())
        assertTrue(segment is ConfinedSegment)

        arena.close()
        assertFalse(arena.isAlive)
    }

    @Test
    fun `test NativeArena ofShared lifecycle`() {
        val arena = NativeArena.ofShared()
        assertTrue(arena.isAlive)

        val segment = arena.allocate(ValueLayout.JAVA_INT)
        assertEquals(4L, segment.byteSize())
        assertTrue(segment is SharedSegment)

        arena.close()
        assertFalse(arena.isAlive)
    }

    @Test
    fun `test NativeArena global lifecycle`() {
        val arena = NativeArena.global()
        assertTrue(arena.isAlive)

        val segment = arena.allocate(ValueLayout.JAVA_LONG, 2)
        assertEquals(16L, segment.byteSize())
        assertTrue(segment is SharedSegment)

        // Global arena close should throw UnsupportedOperationException
        assertFailsWith<UnsupportedOperationException> {
            arena.close()
        }
        assertTrue(arena.isAlive)
    }

    @Test
    fun `test NativeArena allocateFrom String`() {
        NativeArena.ofConfined().use { arena ->
            val segment = arena.allocateFrom("Hello")
            assertEquals(6L, segment.byteSize()) // Including null terminator for C-string allocation from String

            val dest = ByteArray(5)
            ManagedSegment.copy(segment, 0L, dest, 0, 5)
            assertEquals("Hello", String(dest))
        }
    }
}
