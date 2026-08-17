package io.mazewall.profiler.internal

import io.mazewall.profiler.engine.TraceEvent
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraceEventQueueTest {
    @Test
    fun `bounded queue drops newest events instead of growing`() {
        val queue = TraceEventQueue(capacity = 4)
        repeat(4) { i ->
            assertTrue(queue.offer(event(i)))
        }
        assertEquals(0, queue.droppedCount)
        assertFalse(queue.offer(event(99)))
        assertEquals(1, queue.droppedCount)
        assertFalse(queue.offer(event(100)))
        assertEquals(2, queue.droppedCount)
        queue.close()
    }

    private fun event(n: Int): TraceEvent =
        TraceEvent(
            tidValue = n,
            syscallName = "openat",
            args = longArrayOf(),
            paths = emptyList(),
            stackTrace = null,
        )
}
