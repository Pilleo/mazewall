package io.mazewall.enforcer.internal.execution

import io.mazewall.enforcer.api.execution.CancellationDisposition
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.InvocationHandle
import io.mazewall.enforcer.internal.containmentFailureForCaller
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

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
            delivered.complete(action.call())
        } catch (bodyFatal: Error) {
            failure = bodyFatal
            owner.fail(bodyFatal)
            delivered.completeExceptionally(bodyFatal)
        } catch (bodyFailure: Throwable) {
            failure = containmentFailureForCaller(bodyFailure)
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
