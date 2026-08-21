package io.mazewall.profiler.internal

import io.mazewall.profiler.engine.TraceEvent
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded USER_NOTIF event buffer. Never blocks the ACK thread: a full queue
 * drops the newest event and records [droppedCount] so the kernel is still
 * CONTINUEd. Unlimited channels can OOM the profiler JVM.
 */
internal class TraceEventQueue(
    capacity: Int = DEFAULT_CAPACITY,
) {
    val channel: Channel<TraceEvent> = Channel(capacity)
    private val dropped = AtomicLong(0)

    val droppedCount: Long get() = dropped.get()

    fun offer(event: TraceEvent): Boolean {
        val result = channel.trySend(event)
        if (result.isSuccess) {
            return true
        }
        if (result.isClosed) {
            return false
        }
        dropped.incrementAndGet()
        return false
    }

    fun close() {
        channel.close()
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 1024
    }
}
