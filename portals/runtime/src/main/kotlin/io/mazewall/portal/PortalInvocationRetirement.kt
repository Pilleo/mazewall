package io.mazewall.portal

import io.mazewall.platform.lifecycle.Deadline
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** Lexical host dispatch context, never evidence of protection of host mapping code. */
internal class PortalInvocationRetirement(
    private val identity: Any,
    val deadline: Deadline,
) {
    var started = false
        private set
    var failure: Throwable? = null
        private set
    private val retirements = LinkedHashSet<CompletableFuture<Unit>>()

    fun <T> run(action: Callable<T>): T {
        check(current.get() == null) { "nested asynchronous Portal dispatch is unsupported" }
        current.set(this)
        return try {
            action.call()
        } finally {
            current.remove()
        }
    }

    fun awaitRetirement(): Throwable? {
        var interrupted = false
        var cleanupFailure: Throwable? = null
        retirements.forEach { retirement ->
            var done = false
            while (!done) {
                try {
                    retirement.get()
                    done = true
                } catch (cancelled: InterruptedException) {
                    interrupted = true // Cancellation does not abandon the physical owner.
                    cleanupFailure = cleanupFailure?.let {
                        io.mazewall.platform.fd.CleanupFailures
                        .merge(it, cancelled)
                    } ?: cancelled
                } catch (failed: ExecutionException) {
                    val next = failed.cause ?: failed
                    cleanupFailure = cleanupFailure?.let {
                        io.mazewall.platform.fd.CleanupFailures
                        .merge(it, next)
                    } ?: next
                    done = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return cleanupFailure
    }

    companion object {
        private val current = ThreadLocal<PortalInvocationRetirement>()

        fun deadline(identity: Any): Deadline? =
            current
                .get()
                ?.also {
            check(it.identity === identity) { "operation attempted a different prepared Portal broker" }
        }?.deadline

        fun dispatched(identity: Any) {
            current.get()?.let {
                check(it.identity === identity) { "operation attempted a different prepared Portal broker" }
                it.started = true
            }
        }

        fun retain(
            retirement: CompletableFuture<Unit>,
            failure: Throwable? = null,
        ) {
            current.get()?.let {
                it.retirements.add(retirement)
                if (it.failure == null) it.failure = failure
            }
        }
    }
}
