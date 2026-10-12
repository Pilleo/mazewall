package io.mazewall.enforcer.internal.execution

import io.mazewall.enforcer.api.execution.CancellationDisposition
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.InvocationContextSnapshot
import io.mazewall.enforcer.api.execution.InvocationEvidence
import io.mazewall.enforcer.api.execution.InvocationHandle
import io.mazewall.enforcer.internal.containmentFailureForCaller
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference

/** One admission lease; cancellation cannot release a running body's physical lifetime. */
internal class OwnedInvocation<T>(
    private val owner: OwnedDomain,
    private val action: Callable<T>,
) : Runnable,
    InvocationHandle<T> {
    private enum class Phase { QUEUED, RUNNING, ENDED }

    private val lock = Any()
    private var phase = Phase.QUEUED
    private var worker: Thread? = null
    private val delivered = CompletableFuture<T>()
    private val ended = CompletableFuture<ExecutionEnd>()
    private val resultView = delivered.minimalCompletionStage()
    private val endView = ended.minimalCompletionStage()
    private val observedFailure = AtomicReference<Throwable?>()

    fun recordObservedFailure(failure: Throwable) {
        observedFailure.compareAndSet(null, failure)
    }

    private fun contextFailed(failure: Throwable) {
        val primary = observedFailure.updateAndGet {
            io.mazewall.platform.fd.CleanupFailures
            .merge(it, failure)
        }
        owner.fail(checkNotNull(primary))
    }

    @Suppress("TooGenericExceptionCaught") // owned context boundary retains fatal identity and quarantines unproved restoration
    fun <R> withContext(
        snapshot: InvocationContextSnapshot,
        body: Callable<R>,
    ): R {
        val restoration = try {
            snapshot.apply()
        } catch (applicationFailure: Throwable) {
            contextFailed(applicationFailure)
            throw applicationFailure
        }
        var restorationFailure: Throwable? = null
        val outcome = try {
            Result.success(body.call())
        } catch (bodyFailure: Throwable) {
            Result.failure<R>(bodyFailure)
        } finally {
            try {
                restoration.close()
            } catch (cleanup: Throwable) {
                restorationFailure = cleanup
            }
        }
        val primary = outcome.exceptionOrNull()
        val failure = restorationFailure?.let {
            io.mazewall.platform.fd.CleanupFailures
            .merge(primary, it)
        } ?: primary
        if (restorationFailure != null || failure is Error) contextFailed(checkNotNull(failure))
        if (failure != null) throw failure
        return outcome.getOrThrow()
    }

    fun evidence(): InvocationEvidence = InvocationEvidence(owner.generation, observedFailure.get())

    override fun result(): CompletionStage<T> = resultView

    override fun executionEnd(): CompletionStage<ExecutionEnd> = endView

    override fun requestCancellation(): CancellationDisposition {
        val neverStarted = synchronized(lock) {
            when (phase) {
                Phase.ENDED -> return CancellationDisposition.ALREADY_ENDED
                Phase.QUEUED -> {
                    phase = Phase.ENDED
                    true
                }
                Phase.RUNNING -> {
                    checkNotNull(worker).interrupt()
                    false
                }
            }
        }
        val failure = CancellationException("Owned invocation cancellation requested")
        if (neverStarted) finishNotStarted(failure) else delivered.completeExceptionally(failure)
        return CancellationDisposition.REQUESTED
    }

    fun generationFailed(failure: Throwable) {
        val neverStarted = synchronized(lock) {
            when (phase) {
                Phase.ENDED -> return
                Phase.QUEUED -> {
                    phase = Phase.ENDED
                    true
                }
                Phase.RUNNING -> {
                    // The originating body retains canonical classification/delivery after its context cleanup.
                    if (worker === Thread.currentThread()) return
                    checkNotNull(worker).interrupt()
                    false
                }
            }
        }
        if (neverStarted) finishNotStarted(failure) else delivered.completeExceptionally(failure)
    }

    private fun finishNotStarted(failure: Throwable) {
        owner.removeQueued(this)
        owner.finished(this)
        try {
            delivered.completeExceptionally(failure)
        } finally {
            ended.complete(ExecutionEnd.NotStarted(failure))
        }
    }

    @Suppress("TooGenericExceptionCaught") // owned body boundary retains original fatal identity and physical lease
    override fun run() {
        synchronized(lock) {
            if (phase == Phase.ENDED) return
            check(phase == Phase.QUEUED)
            phase = Phase.RUNNING
            worker = Thread.currentThread()
        }
        var failure: Throwable? = null
        try {
            val value = RuntimeWorkerContext.withInvocation(this) { action.call() }
            observedFailure.get()?.let { throw it }
            delivered.complete(value)
        } catch (bodyFailure: Throwable) {
            failure = containmentFailureForCaller(
                io.mazewall.platform.fd.CleanupFailures
                .merge(observedFailure.get(), bodyFailure),
            )
            if (failure is Error) owner.fail(failure)
            delivered.completeExceptionally(failure)
        } finally {
            synchronized(lock) {
                phase = Phase.ENDED
                worker = null
            }
            Thread.interrupted()
            owner.finished(this)
            ended.complete(ExecutionEnd.Finished(failure))
        }
        if (failure is Error) throw failure
    }
}
