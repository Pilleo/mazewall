package io.mazewall.enforcer.internal.execution

import io.mazewall.enforcer.api.execution.CancellationDisposition
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.InvocationContextSnapshot
import io.mazewall.enforcer.api.execution.InvocationEvidence
import io.mazewall.enforcer.api.execution.InvocationHandle
import io.mazewall.enforcer.internal.containmentFailureForCaller
import io.mazewall.platform.fd.CleanupFailures
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference

/** One admission lease; cancellation cannot release a running body's physical lifetime. */
internal class OwnedInvocation<T>(
    private val owner: OwnedDomain,
    private val action: Callable<T>,
    private val cancellationPermit: Any,
) : Runnable,
    InvocationHandle<T> {
    private enum class Phase { QUEUED, RUNNING, ENDED }

    internal enum class LiveCancellation { NOT_STARTED, RUNNING, ENDED }

    private val lock = Any()
    private var phase = Phase.QUEUED
    private var worker: Thread? = null
    private var liveCancellationWorker: Thread? = null
    private val delivered = CompletableFuture<T>()
    private val ended = CompletableFuture<ExecutionEnd>()
    private val resultView = delivered.minimalCompletionStage()
    private val endView = ended.minimalCompletionStage()
    private val observedFailure = AtomicReference<Throwable?>()
    private var taskReceipt: io.mazewall.platform.native.TaskBeginReceipt? = null
    private var taskLease: io.mazewall.platform.native.TaskLease? = null
    private var taskEndAttempted = false
    private var taskEnd: io.mazewall.platform.native.LiveCompletion? = null
    private val liveRunState = if (owner.scoping is FrozenStackScoping.Live) LiveRunState() else null
    private val liveCancellationFailure = if (liveRunState != null) CancellationException("Owned invocation cancellation requested") else null

    fun recordObservedFailure(failure: Throwable) {
        observedFailure.compareAndSet(null, failure)
    }

    private fun contextFailed(failure: Throwable) {
        val primary = observedFailure.updateAndGet {
            CleanupFailures.merge(it, failure)
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
            CleanupFailures.merge(primary, it)
        } ?: primary
        if (restorationFailure != null || failure is Error) contextFailed(checkNotNull(failure))
        if (failure != null) throw failure
        return outcome.getOrThrow()
    }

    fun evidence(): InvocationEvidence = InvocationEvidence(owner.generation, observedFailure.get())

    override fun result(): CompletionStage<T> = resultView

    override fun executionEnd(): CompletionStage<ExecutionEnd> = endView

    internal fun belongsTo(domain: OwnedDomain): Boolean = owner === domain

    /** Called only on the original worker; these fields are worker-owned. */
    internal fun retainsLiveLease(lease: io.mazewall.platform.native.TaskLease): Boolean =
        taskLease === lease && taskReceipt != null && !taskEndAttempted && RuntimeWorkerContext.liveWorker === lease.worker

    override fun requestCancellation(): CancellationDisposition {
        if (liveRunState != null) return requestLiveCancellation()
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
        owner.invocationMetrics.recordCancellation()
        try {
            owner.closeLiveAdmission()
        } finally {
            if (neverStarted) finishNotStarted(failure) else deliverFailure(failure)
        }
        return CancellationDisposition.REQUESTED
    }

    internal fun markLiveCancellation(permit: Any): LiveCancellation =
        synchronized(lock) {
            check(permit === cancellationPermit) { "Live cancellation permit does not match" }
            when (phase) {
                Phase.ENDED -> LiveCancellation.ENDED
                Phase.QUEUED -> {
                    phase = Phase.ENDED
                    LiveCancellation.NOT_STARTED
                }
                Phase.RUNNING -> {
                    liveCancellationWorker = checkNotNull(worker)
                    LiveCancellation.RUNNING
                }
            }
        }

    @Suppress("TooGenericExceptionCaught") // Preserve every cancellation effect failure while completing ownership bookkeeping.
    private fun requestLiveCancellation(): CancellationDisposition {
        val transition = owner.cancelLiveInvocation(this, cancellationPermit)
        if (transition == LiveCancellation.ENDED) return CancellationDisposition.ALREADY_ENDED
        var problem: Throwable? = null
        var interrupted = false
        try {
            if (transition == LiveCancellation.RUNNING) checkNotNull(liveCancellationWorker).interrupt()
        } catch (failure: Throwable) {
            problem = failure
            interrupted = wasInterrupted(failure)
        }
        owner.invocationMetrics.recordCancellation()
        try {
            owner.closeLiveAdmission()
        } catch (failure: Throwable) {
            problem = CleanupFailures.merge(problem, failure)
            interrupted = interrupted || wasInterrupted(failure)
        }
        try {
            val failure = checkNotNull(liveCancellationFailure)
            if (transition == LiveCancellation.NOT_STARTED) finishNotStarted(failure) else deliverFailure(failure)
        } catch (failure: Throwable) {
            problem = CleanupFailures.merge(problem, failure)
            interrupted = interrupted || wasInterrupted(failure)
        }
        if (interrupted) Thread.currentThread().interrupt()
        if (problem != null) throw problem
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
        if (neverStarted) finishNotStarted(failure) else deliverFailure(failure)
    }

    private fun finishNotStarted(failure: Throwable) {
        owner.finished(this, removeQueued = true)
        try {
            deliverFailure(failure)
        } finally {
            if (ended.complete(ExecutionEnd.NotStarted(failure))) owner.invocationMetrics.recordEnd()
        }
    }

    private fun deliverFailure(failure: Throwable) {
        if (delivered.completeExceptionally(failure)) owner.invocationMetrics.recordDelivery()
    }

    @Suppress("TooGenericExceptionCaught") // owned body boundary retains original fatal identity and physical lease
    override fun run() {
        val liveState = liveRunState
        synchronized(lock) {
            if (phase == Phase.ENDED) return
            check(phase == Phase.QUEUED)
            phase = Phase.RUNNING
            worker = Thread.currentThread()
        }
        if (liveState != null) {
            runLive(liveState)
            return
        }
        var failure: Throwable? = null
        try {
            val value = RuntimeWorkerContext.withInvocation(this) { action.call() }
            observedFailure.get()?.let { throw it }
            if (delivered.complete(value)) owner.invocationMetrics.recordDelivery()
        } catch (bodyFailure: Throwable) {
            failure = containmentFailureForCaller(
                CleanupFailures.merge(observedFailure.get(), bodyFailure),
            )
            if (failure is Error) owner.fail(failure)
            deliverFailure(failure)
        } finally {
            synchronized(lock) {
                phase = Phase.ENDED
                worker = null
            }
            Thread.interrupted()
            owner.finished(this)
            if (ended.complete(ExecutionEnd.Finished(failure))) owner.invocationMetrics.recordEnd()
        }
        if (failure is Error) throw failure
    }

    private class LiveRunState {
        var failure: Throwable? = null
        var interrupted = false
        var slot: LiveWorkerSlot? = null
        var quarantined = false
    }

    @Suppress("TooGenericExceptionCaught") // Exact task receipt and fatal identity survive every post-Begin path.
    private fun runLive(state: LiveRunState) {
        try {
            val lease = beginLiveTask(state)
            deliverLiveTask(state, lease)
        } catch (problem: Throwable) {
            if (wasInterrupted(problem)) state.interrupted = true
            state.failure = CleanupFailures.merge(state.failure, problem)
            quarantineLive(state)
            deliverFailure(checkNotNull(state.failure))
        } finally {
            val lease = taskLease
            if (lease != null && !taskEndAttempted) {
                try {
                    endLiveTask(checkNotNull(state.slot), lease)
                } catch (ending: Throwable) {
                    if (wasInterrupted(ending)) state.interrupted = true
                    state.failure = CleanupFailures.merge(state.failure, ending)
                }
            }
            finishLiveTask(state)
        }
        val failure = state.failure
        if (failure is Error) throw failure
    }

    @Suppress("TooGenericExceptionCaught") // Recovery stays on the Begin worker and preserves the original failure.
    private fun beginLiveTask(state: LiveRunState): io.mazewall.platform.native.TaskLease {
        val slot = owner.liveTaskAdmission.claimLiveTask(this)
        state.slot = slot
        val workerOwner = checkNotNull(RuntimeWorkerContext.liveWorker)
        val receipt = workerOwner.owner.capability.prepareTaskReceipt(workerOwner)
        taskReceipt = receipt
        val result = try {
            workerOwner.owner.capability.beginCurrent(workerOwner, receipt)
        } catch (problem: Throwable) {
            recoverFailedLiveBegin(slot, workerOwner, receipt, problem)
        }
        val lease = when (result) {
            is io.mazewall.platform.native.LiveSetup.Ready -> result.value
            is io.mazewall.platform.native.LiveSetup.Refused -> recoverFailedLiveBegin(
                slot,
                workerOwner,
                receipt,
                IllegalStateException("Live task begin refused: ${result.failure}", result.failure.cause),
            )
        }
        check(lease.worker === workerOwner) { "Live task belongs to a different worker" }
        taskLease = lease
        owner.liveTaskAdmission.publishLiveTask(slot, this, lease)
        return lease
    }

    @Suppress("TooGenericExceptionCaught") // A validated lease must receive one End even if context setup fails.
    private fun deliverLiveTask(state: LiveRunState, lease: io.mazewall.platform.native.TaskLease) {
        var contextFailure: Throwable? = null
        try {
            RuntimeWorkerContext.withInvocation(this) {
                RuntimeWorkerContext.withLiveTask(lease) { runLiveBody(state, lease) }
            }
        } catch (problem: Throwable) {
            contextFailure = problem
        } finally {
            if (!taskEndAttempted) {
                try {
                    endLiveTask(checkNotNull(state.slot), lease)
                } catch (ending: Throwable) {
                    if (wasInterrupted(ending)) state.interrupted = true
                    contextFailure = CleanupFailures.merge(contextFailure, ending)
                }
            }
        }
        contextFailure?.let { throw it }
    }

    @Suppress("TooGenericExceptionCaught") // Delivery and End retain original body and cleanup failures.
    private fun runLiveBody(state: LiveRunState, lease: io.mazewall.platform.native.TaskLease) {
        try {
            val value = action.call()
            observedFailure.get()?.let { throw it }
            if (delivered.complete(value)) owner.invocationMetrics.recordDelivery()
        } catch (bodyFailure: Throwable) {
            if (wasInterrupted(bodyFailure)) state.interrupted = true
            state.failure = containmentFailureForCaller(CleanupFailures.merge(observedFailure.get(), bodyFailure))
            if (state.failure is Error) quarantineLive(state)
            deliverFailure(checkNotNull(state.failure))
        } finally {
            observedFailure.get()?.let { late ->
                state.failure = containmentFailureForCaller(CleanupFailures.merge(state.failure, late))
            }
            try {
                endLiveTask(checkNotNull(state.slot), lease)
            } catch (ending: Throwable) {
                if (wasInterrupted(ending)) state.interrupted = true
                state.failure = CleanupFailures.merge(state.failure, ending)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Quarantine retains both task and invalidation failure before physical bookkeeping.
    private fun quarantineLive(state: LiveRunState) {
        if (state.quarantined) return
        state.quarantined = true
        try {
            owner.fail(checkNotNull(state.failure))
        } catch (cleanup: Throwable) {
            if (wasInterrupted(cleanup)) state.interrupted = true
            state.failure = CleanupFailures.merge(state.failure, cleanup)
        }
    }

    private fun finishLiveTask(state: LiveRunState) {
        val failure = state.failure
        val slot = state.slot
        if (failure != null) {
            if (failure is Error || (slot != null && slot.activeTask === this)) quarantineLive(state)
        }
        synchronized(lock) {
            phase = Phase.ENDED
            worker = null
        }
        state.interrupted = Thread.interrupted() || state.interrupted
        try {
            owner.finished(this)
            if (ended.complete(ExecutionEnd.Finished(state.failure))) owner.invocationMetrics.recordEnd()
        } finally {
            if (state.interrupted) Thread.currentThread().interrupt()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Preserve the original Begin failure while recovering the exact attached task.
    private fun recoverFailedLiveBegin(
        slot: LiveWorkerSlot,
        worker: io.mazewall.platform.native.WorkerOwner,
        receipt: io.mazewall.platform.native.TaskBeginReceipt,
        original: Throwable,
    ): Nothing {
        var failure = original
        val recovered = try {
            worker.owner.capability.recoverTask(receipt)
        } catch (recovery: Throwable) {
            restoreLiveInterrupt(recovery)
            failure = CleanupFailures.merge(original, recovery)
            null
        }
        if (recovered is io.mazewall.platform.native.LiveSetup.Ready) {
            if (recovered.value.worker === worker) {
                taskLease = recovered.value
                try {
                    owner.liveTaskAdmission.registerLiveCleanup(slot, this, recovered.value)
                    endLiveTask(slot, recovered.value)
                } catch (ending: Throwable) {
                    restoreLiveInterrupt(ending)
                    failure = CleanupFailures.merge(original, ending)
                }
            }
        }
        throw failure
    }

    private fun endLiveTask(
        slot: LiveWorkerSlot,
        lease: io.mazewall.platform.native.TaskLease,
    ) {
        check(!taskEndAttempted && taskLease === lease && taskReceipt != null && taskEnd == null)
        owner.liveTaskAdmission.revokeLiveTask(slot, this, lease)
        taskEndAttempted = true
        val outcome = lease.worker.owner.capability
            .endCurrent(lease)
        taskEnd = outcome
        when (outcome) {
            io.mazewall.platform.native.LiveCompletion.Complete -> {
                slot.complete(this)
                taskLease = null
                taskReceipt = null
            }
            is io.mazewall.platform.native.LiveCompletion.Pending -> {
                owner.closeLiveAdmission()
                throw LiveTaskEndPending(outcome)
            }
            is io.mazewall.platform.native.LiveCompletion.Refused -> {
                owner.closeLiveAdmission()
                throw IllegalStateException("Live task end refused: ${outcome.failure}", outcome.failure.cause)
            }
        }
    }
}

private fun wasInterrupted(failure: Throwable): Boolean = failure is InterruptedException

private fun restoreLiveInterrupt(failure: Throwable) {
    if (wasInterrupted(failure)) Thread.currentThread().interrupt()
}

/** Pending is a retained native observation, not a native failure. */
private class LiveTaskEndPending(
    val observation: io.mazewall.platform.native.LiveCompletion.Pending,
) : IllegalStateException("Live task end remains pending: ${observation.reason}")
