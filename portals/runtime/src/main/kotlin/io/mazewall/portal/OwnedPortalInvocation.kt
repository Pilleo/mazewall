package io.mazewall.portal

import io.mazewall.enforcer.api.execution.CancellationDisposition
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.InvocationHandle
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

internal class OwnedPortalInvocation<T>(
    private val owner: PortalOperationOwner,
    private val retirement: PortalInvocationRetirement,
    private val action: Callable<T>,
) : Runnable,
    InvocationHandle<T> {
    private val lock = Any()
    private var phase = PortalInvocationPhase.QUEUED
    private var thread: Thread? = null
    private val delivered = CompletableFuture<T>()
    private val ended = CompletableFuture<ExecutionEnd>()
    private val resultView = delivered.minimalCompletionStage()
    private val endView = ended.minimalCompletionStage()

    override fun result(): CompletionStage<T> = resultView

    override fun executionEnd(): CompletionStage<ExecutionEnd> = endView

    override fun requestCancellation(): CancellationDisposition {
        val queued = synchronized(lock) {
            when (phase) {
                PortalInvocationPhase.ENDED -> return CancellationDisposition.ALREADY_ENDED
                PortalInvocationPhase.QUEUED -> {
                    phase = PortalInvocationLifecycle.evaluate(phase, PortalInvocationEvent.CANCEL)
                    true
                }
                PortalInvocationPhase.RUNNING -> {
                    checkNotNull(thread).interrupt()
                    false
                }
                PortalInvocationPhase.RETIRING -> false
            }
        }
        val failure = CancellationException("Portal invocation cancellation requested")
        owner.recordCancellation()
        if (queued) finishNotStarted(failure) else deliverFailure(failure)
        return CancellationDisposition.REQUESTED
    }

    fun reject(failure: Throwable) {
        val rejected = synchronized(lock) {
            if (phase != PortalInvocationPhase.QUEUED) {
                false
            } else {
                phase = PortalInvocationLifecycle.evaluate(phase, PortalInvocationEvent.CANCEL)
                true
            }
        }
        if (rejected) finishNotStarted(failure)
    }

    private fun finishNotStarted(failure: Throwable) {
        owner.finished(this)
        if (ended.complete(ExecutionEnd.NotStarted(failure))) owner.recordEnd()
        deliverFailure(failure)
    }

    private fun deliverFailure(failure: Throwable) {
        if (delivered.completeExceptionally(failure)) owner.recordDelivery()
    }

    @Suppress("TooGenericExceptionCaught") // Fatal host mapping failure still retains exact process retirement ownership.
    override fun run() {
        synchronized(lock) {
            if (phase != PortalInvocationPhase.QUEUED) return
            phase = PortalInvocationLifecycle.evaluate(phase, PortalInvocationEvent.START)
            thread = Thread.currentThread()
        }
        var failure: Throwable? = null
        try {
            check(retirement.deadline.remainingMillis() > 0) { "Portal operation expired before mapping began" }
            val value = retirement.run(action)
            retirement.failure?.let { throw it }
            check(retirement.started) { "Portal operation mapping returned without invoking its prepared worker" }
            if (delivered.complete(value)) owner.recordDelivery()
        } catch (bodyFailure: Throwable) {
            failure = bodyFailure
            deliverFailure(bodyFailure)
        } finally {
            synchronized(lock) {
                phase = PortalInvocationLifecycle.evaluate(phase, PortalInvocationEvent.BODY_ENDED)
                thread = null
            }
            retirement.awaitRetirement()?.let { cleanup ->
                failure = failure?.let {
                    io.mazewall.platform.fd.CleanupFailures
                    .merge(it, cleanup)
                } ?: cleanup
            }
            synchronized(lock) { phase = PortalInvocationLifecycle.evaluate(phase, PortalInvocationEvent.RETIREMENT_ENDED) }
            Thread.interrupted()
            owner.finished(this)
            val outcome = if (retirement.started) ExecutionEnd.Finished(failure) else ExecutionEnd.NotStarted(checkNotNull(failure))
            if (ended.complete(outcome)) owner.recordEnd()
        }
    }
}
