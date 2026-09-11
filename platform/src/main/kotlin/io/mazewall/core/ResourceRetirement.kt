package io.mazewall.core

/** Local cleanup fold: attempt every independent release, then propagate failures, fatal first. */
internal class ResourceRetirement {
    companion object {
        /** Preserve the startup failure unless rollback discovers a fatal failure. */
        fun <T> runWithRollback(
            action: () -> T,
            rollback: () -> Unit,
        ): T {
            return try {
                action()
            } catch (expectedStartupFailure: Throwable) {
                val cleanup = ResourceRetirement()
                cleanup.record(expectedStartupFailure)
                cleanup.attempt(rollback)
                throw checkNotNull(cleanup.finishFailure())
            }
        }
    }

    private var interrupted = Thread.interrupted()
    private var failure: Throwable? = null

    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (expectedCleanupFailure: Throwable) {
            record(expectedCleanupFailure)
        }
    }

    private fun record(next: Throwable) {
        if (next is InterruptedException) {
            interrupted = true
            Thread.interrupted()
        }
        failure = CleanupFailures.merge(failure, next)
    }

    fun finish() {
        finishFailure()?.let { throw it }
    }

    private fun finishFailure(): Throwable? {
        if (interrupted) Thread.currentThread().interrupt()
        return failure
    }
}
