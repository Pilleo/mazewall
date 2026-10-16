package io.mazewall.enforcer.internal.execution

import java.time.Duration
import java.util.concurrent.TimeUnit

internal class Deadline(
    timeout: Duration,
) {
    private val end = System.nanoTime() + timeout.toNanos().also { require(it >= 0L) { "Timeout cannot be negative" } }

    fun remainingNanos(): Long = (end - System.nanoTime()).coerceAtLeast(0L)

    fun join(thread: Thread) {
        val remaining = remainingNanos()
        if (thread.isAlive && remaining > 0L) {
            val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
            thread.join(millis, (remaining - TimeUnit.MILLISECONDS.toNanos(millis)).toInt())
        }
    }
}
