package io.mazewall.ffi.memory

import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManagedSegmentTest {
    @Test
    fun `test ConfinedSegment segment access`() {
        Arena.ofConfined().use { arena ->
            val raw = arena.allocate(10)
            val confined = ConfinedSegment(raw)
            assertEquals(raw, confined.native)
            assertEquals(raw.address(), confined.address())
            assertEquals(10L, confined.byteSize())
            @Suppress("USELESS_IS_CHECK")
            assertTrue(confined is ManagedSegment)
        }
    }

    @Test
    fun `test SharedSegment segment access`() {
        Arena.ofShared().use { arena ->
            val raw = arena.allocate(10)
            val shared = SharedSegment(raw)
            assertEquals(raw, shared.native)
            assertEquals(raw.address(), shared.address())
            assertEquals(10L, shared.byteSize())
            @Suppress("USELESS_IS_CHECK")
            assertTrue(shared is ManagedSegment)
        }
    }

    @Test
    fun `test ManagedSegment NULL constant`() {
        val nullSeg = ManagedSegment.NULL
        assertEquals(0L, nullSeg.address())
        assertEquals(0L, nullSeg.byteSize())
        assertEquals(MemorySegment.NULL, nullSeg.native)
        assertTrue(nullSeg is SharedSegment)
    }

    @Test
    fun `test ManagedSegment copy operations`() {
        Arena.ofConfined().use { arena ->
            val raw = arena.allocate(5)
            val segment = ConfinedSegment(raw)

            val srcBytes = byteArrayOf(10, 20, 30, 40, 50)
            ManagedSegment.copy(srcBytes, 0, segment, 0L, 5)

            val destBytes = ByteArray(5)
            ManagedSegment.copy(segment, 0L, destBytes, 0, 5)

            assertContentEquals(srcBytes, destBytes)
        }
    }
}
