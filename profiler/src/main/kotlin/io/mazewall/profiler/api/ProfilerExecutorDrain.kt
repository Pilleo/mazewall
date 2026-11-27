package io.mazewall.profiler.api

import io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Observes physical executor termination before retiring the session's collectors. */
internal class ProfilerExecutorDrain(
    private val executor: ExecutorService,
    private val session: ProfilerSession,
) {
    private val retirementLock = Any()
    private var retirement: CompletableFuture<SeccompCleanupOutcome>? = null

    init {
        session.bindExecutor(executor)
    }

    fun beginRetirement(): CompletableFuture<SeccompCleanupOutcome> =
        synchronized(retirementLock) {
            val current = retirement
            if (current != null && (!current.isDone || (!current.isCompletedExceptionally && current.join() == SeccompCleanupOutcome.Complete))) {
                return@synchronized current
            }
            val completion = CompletableFuture<SeccompCleanupOutcome>()
            Thread.ofVirtual().name("mazewall-profiler-executor-drain").start { retire(completion) }
            retirement = completion
            completion
        }

    @Suppress("TooGenericExceptionCaught") // Preserve all cleanup failures for the awaiting caller.
    private fun retire(completion: CompletableFuture<SeccompCleanupOutcome>) {
        try {
            while (!executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {
                // Repeat only after an elapsed wait, never on Future completion or cancellation.
            }
            completion.complete(session.closeWithResult())
        } catch (failure: Throwable) {
            completion.completeExceptionally(failure)
        }
    }

    fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ): Boolean {
        val started = System.nanoTime()
        if (!executor.awaitTermination(timeout, unit)) return false
        val remaining = unit.toNanos(timeout).coerceAtLeast(0) - (System.nanoTime() - started)
        val completion = synchronized(retirementLock) { retirement ?: beginRetirement() }
        return try {
            completion
                .get(remaining.coerceAtLeast(0), TimeUnit.NANOSECONDS)
                .throwIfIncomplete("Profiler executor session cleanup failed")
            true
        } catch (_: TimeoutException) {
            false
        } catch (failed: ExecutionException) {
            throw failed.cause ?: failed
        }
    }
}
