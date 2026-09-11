package io.mazewall.platform.seccomp.daemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NotificationLivenessMetricsTest {
    @Test
    fun `concurrent updates retain every event and earlier snapshots are immutable`() {
        val counters = NotificationLivenessCounters()
        val before = counters.snapshot()
        Executors.newFixedThreadPool(4).use { executor ->
            val tasks = List(4) {
                executor.submit { repeat(1_000) { counters.started() } }
            }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        }
        assertEquals(0, before.started)
        assertEquals(4_000, counters.snapshot().started)
    }

    @Test
    fun `counters saturate without wrapping negative`() {
        val counters = NotificationLivenessCounters(NotificationLivenessSnapshot(started = Long.MAX_VALUE))
        counters.started()
        assertEquals(Long.MAX_VALUE, counters.snapshot().started)
    }

    @Test
    fun `pending deadline and latency observations contain only aggregate values`() {
        val counters = NotificationLivenessCounters()

        counters.started()
        counters.reactorPoll()
        counters.observePending(ageNanos = 25_000_000_000L, deadlineNear = true)
        counters.observePending(ageNanos = 26_000_000_000L, deadlineNear = false)
        counters.replyLatency(27_000_000_000L)
        counters.helperSaturated()
        counters.finished()

        assertEquals(
            NotificationLivenessSnapshot(
                started = 1,
                pending = 0,
                oldestPendingAgeNanos = 26_000_000_000L,
                deadlineNear = 1,
                replyLatencyCount = 1,
                replyLatencyNanos = 27_000_000_000L,
                maxReplyLatencyNanos = 27_000_000_000L,
                reactorPolls = 1,
                helperSaturation = 1,
            ),
            counters.snapshot(),
        )
    }
}
