package io.mazewall.enforcer.supervisor

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
        val prior = failure
        if (prior == null) {
            failure = next
        } else if (next is Error && prior !is Error) {
            next.addSuppressed(prior)
            failure = next
        } else if (next !== prior) {
            prior.addSuppressed(next)
        }
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
