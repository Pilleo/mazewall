package io.mazewall.profiler.internal

import java.time.Duration
import java.util.concurrent.TimeUnit

/** JVM hooks run concurrently; a child must outlive sessions owned by application shutdown. */
internal class ProfilerDaemonLifetime {
    private enum class Admission { OPEN, CLOSING }

    private data class State(
        val admission: Admission,
        val sessions: Int,
    ) {
        fun acquire(): State =
            when (admission) {
            Admission.OPEN -> copy(sessions = Math.incrementExact(sessions))
            Admission.CLOSING -> error("Profiler daemon is shutting down")
        }

        fun release(): State {
            check(sessions > 0) { "Profiler session lease already retired" }
            return copy(sessions = sessions - 1)
        }
    }

    private val gate = Object()
    private var state = State(Admission.OPEN, 0)

    fun acquire(): AutoCloseable =
        synchronized(gate) {
        state = state.acquire()
        var released = false
        AutoCloseable {
            synchronized(gate) {
                if (!released) {
                    state = state.release()
                    released = true
                    gate.notifyAll()
                }
            }
        }
    }

    fun awaitSessions(timeout: Duration): Boolean =
        synchronized(gate) {
        require(!timeout.isNegative)
        state = state.copy(admission = Admission.CLOSING)
        val deadline = System.nanoTime() + timeout.toNanos()
        while (state.sessions > 0) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return@synchronized false
            try {
                TimeUnit.NANOSECONDS.timedWait(gate, remaining)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@synchronized false
            }
        }
        true
    }
}
