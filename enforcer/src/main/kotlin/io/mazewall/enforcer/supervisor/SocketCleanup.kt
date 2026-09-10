package io.mazewall.enforcer.supervisor

import io.mazewall.core.CleanupFailures

/** Cleanup attempts independent owners after failure; fatal errors retain primary identity. */
internal class SocketCleanup(
    initial: Throwable? = null,
) {
    var failure: Throwable? = initial
        private set

    @Suppress("TooGenericExceptionCaught") // Resource boundary: retain and rethrow after independent cleanup.
    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (fatal: Error) {
            record(fatal)
        } catch (ordinary: Exception) {
            record(ordinary)
        }
    }

    private fun record(next: Throwable) {
        failure = CleanupFailures.merge(failure, next)
        if (next is InterruptedException) Thread.currentThread().interrupt()
    }

    fun rethrow() {
        failure?.let { throw it }
    }

    companion object {
        /** Cleanup owns the deadline even when interrupted; restore the flag after draining. */
        fun await(
            timeoutNanos: Long,
            wait: (Long) -> Boolean,
        ) {
            var interrupted = Thread.interrupted()
            val deadline = System.nanoTime() + timeoutNanos
            try {
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    check(remaining > 0) { "Socket helper cleanup timed out" }
                    try {
                        check(wait(remaining)) { "Socket helper cleanup timed out" }
                        return
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
