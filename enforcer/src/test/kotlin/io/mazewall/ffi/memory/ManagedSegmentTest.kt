package io.mazewall.ffi.memory

import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManagedSegmentTest {
    @Test
    fun `test ConfinedSegment segment access`() {
        Arena.ofConfined().use { arena ->
            val raw = arena.allocate(10)
            val confined = ConfinedSegment(raw)
            assertEquals(raw, confined.native)
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
            @Suppress("USELESS_IS_CHECK")
            assertTrue(shared is ManagedSegment)
        }
    }
}
