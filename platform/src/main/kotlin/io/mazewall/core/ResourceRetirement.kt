package io.mazewall.core

/** Local cleanup fold: attempt every independent release, then propagate failures, fatal first. */
internal class ResourceRetirement {
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
        val previous = failure
        if (previous == null) {
            failure = next
        } else if (next is Error && previous !is Error) {
            next.addSuppressed(previous)
            failure = next
        } else if (previous !== next) {
            previous.addSuppressed(next)
        }
    }

    fun finish() {
        if (interrupted) Thread.currentThread().interrupt()
        failure?.let { throw it }
    }
}
