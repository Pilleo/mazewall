package io.mazewall.profiler

import io.mazewall.PolicyDefinition
import io.mazewall.core.FdOwnership
import io.mazewall.core.FdState
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.enforcer.diagnostics.validateNotVirtual
import io.mazewall.ffi.networking.SeccompInstallationGate
import io.mazewall.getFdOrThrow
import io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome
import io.mazewall.profiler.compiler.BobCompiler
import io.mazewall.profiler.engine.TraceEvent
import io.mazewall.profiler.internal.DaemonContext
import io.mazewall.profiler.internal.ProfilerTraceListener
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one operation's listeners, TID mappings, buffered observations, and cleanup result. */
internal class ProfilerSession(
    private val runtime: ProfilerRuntime,
    private val context: DaemonContext,
    private val policy: PolicyDefinition<*>,
    private val processWide: Boolean,
    private val captureStackTraces: Boolean,
    private val onClosed: (ProfilerSession) -> Unit,
) : AutoCloseable {
    val logs = CopyOnWriteArrayList<TraceEvent>()
    val stackProfiles = ConcurrentHashMap<TraceEvent, MutableList<Array<StackTraceElement>>>()
    val state = ProfilerSessionState()
    private val pathCache = ConcurrentHashMap<String, Long>()
    private val listeners = CopyOnWriteArrayList<ProfilerTraceListener>()
    private val appliedThreads = ConcurrentHashMap.newKeySet<Thread>()
    private val lifecycleLock = Any()
    private val cleanupLock = Any()
    private val closed = AtomicBoolean(false)
    private var closureNotified = false
    private var installations = 0
    private val installer = runtime.installer.forSession(
        SeccompInstallationGate { operation ->
            synchronized(lifecycleLock) {
                check(!closed.get()) { "Profiler session closed during installation" }
                operation()
            }
        },
    )

    @Volatile
    var cleanupOutcome: SeccompCleanupOutcome = SeccompCleanupOutcome.Complete
        private set

    fun installOnCurrentThread() {
        val thread = Thread.currentThread()
        synchronized(lifecycleLock) {
            // Existing wrapped workers retain their kernel filters in pass-through after session close.
            if (closed.get() || thread in appliedThreads) return
        }
        validateNotVirtual()
        val tid = runtime.tid()
        synchronized(lifecycleLock) {
            if (closed.get()) return
            state.register(tid, thread)
            installations++
        }
        var installed = false
        try {
            installer.installProfilingFilterForThread(
                context.socketPath,
                policy,
                logs,
                if (captureStackTraces) stackProfiles else null,
                pathCache,
                processWide,
            ) { fd, _, _, _, ready ->
                val dispose = synchronized(lifecycleLock) {
                    val late = closed.get()
                    // A late socket still needs the ACK/pass-through protocol, but cannot publish
                    // observations or resolve TIDs through an already closed session.
                    val listener = ProfilerTraceListener(
                        fd,
                        if (late) CopyOnWriteArrayList() else logs,
                        if (!late && captureStackTraces) stackProfiles else null,
                        if (late) ConcurrentHashMap() else pathCache,
                        if (late) {
                            { null }
                        } else {
                            state::threadFor
                        },
                        runtime.native,
                    )
                    listeners.add(listener)
                    // start only launches the uncontained reader/collector; it does not await I/O.
                    listener.start(ready)
                    late
                }
                if (dispose) {
                    closeWithResult()
                    error("Profiler session closed during installation")
                }
            }
            synchronized(lifecycleLock) {
                check(!closed.get()) { "Profiler session closed during installation" }
                appliedThreads.add(thread)
                installed = true
            }
        } finally {
            val dispose = synchronized(lifecycleLock) {
                if (!installed) state.remove(tid)
                installations--
                closed.get()
            }
            if (dispose) closeWithResult()
        }
    }

    @Suppress("TooGenericExceptionCaught") // Record aborted cleanup before propagating the original fatal error.
    fun closeWithResult(): SeccompCleanupOutcome {
        synchronized(lifecycleLock) { closed.set(true) }
        return synchronized(cleanupLock) {
            try {
                listeners.forEach { it.closeWithResult() }
                cleanupOutcome = currentCleanupOutcome()
                val settled = synchronized(lifecycleLock) {
                    state.clear()
                    appliedThreads.clear()
                    installations == 0
                }
                // Aborted folds and late installations retain their service owner and TID resolver.
                if (settled && cleanupOutcome == SeccompCleanupOutcome.Complete && !closureNotified) {
                    onClosed(this)
                    closureNotified = true
                }
            } catch (failure: Exception) {
                cleanupOutcome = currentCleanupOutcome(failure)
            } catch (failure: Error) {
                cleanupOutcome = currentCleanupOutcome(failure)
                throw failure
            }
            cleanupOutcome
        }
    }

    fun retryableOwnership(): List<FileDescriptor<*, FdState.Open, FdOwnership.Owned>> = listeners.flatMap { it.retryableOwnership() }

    private fun currentCleanupOutcome(failure: Throwable? = null): SeccompCleanupOutcome = profilerCleanupOutcome(listeners.map { it.cleanupOutcome }, retryableOwnership(), failure)

    override fun close() {
        closeWithResult().throwIfIncomplete("Profiler session cleanup failed")
    }

    fun compileBillOfBehavior(): BillOfBehavior = BobCompiler.compile(logs)

    fun <T> result(value: T): ProfilingResult<T> {
        val observations = logs.map(ProfileObservation::fromTraceEvent)
        val coverage = ProfilingCoverage.infer(
            strategy = ProfileStrategy.USER_NOTIF,
            strategyReason = "Profiler.profile USER_NOTIF session",
            processWide = processWide,
            observations = observations,
            stacks = if (captureStackTraces) StackAttribution.CAPTURED else StackAttribution.SKIPPED,
            droppedEvents = listeners.sumOf { it.eventQueue.droppedCount.toInt() + it.droppedEvents },
            drainComplete = listeners.isNotEmpty() && listeners.all { it.drainComplete },
            environment = runtime.environment(),
        )
        return ProfilingResult(value, compileBillOfBehavior().copy(stackProfile = stackProfiles), stackProfiles, coverage, observations)
    }
}

/** Include unattempted owners when an earlier close aborted the fold. Fatal errors remain causes. */
internal fun profilerCleanupOutcome(
    outcomes: List<SeccompCleanupOutcome>,
    retryableOwnership: List<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>,
    failure: Throwable? = null,
): SeccompCleanupOutcome {
    val failures = outcomes.filterIsInstance<SeccompCleanupOutcome.Incomplete>().flatMap { it.failures }.toMutableList()
    if (failure != null && failures.none { it === failure || it.cause === failure }) {
        failures += if (failure is Exception) failure else IllegalStateException("Profiler cleanup aborted", failure)
    }
    return if (failures.isEmpty() && retryableOwnership.isEmpty()) {
        SeccompCleanupOutcome.Complete
    } else {
        SeccompCleanupOutcome.Incomplete(failures.toList(), retryableOwnership)
    }
}

/** Cleanup owners publish the typed result before adapting it to the compatible throwing close API. */
internal fun SeccompCleanupOutcome.throwIfIncomplete(message: String) {
    when (this) {
        SeccompCleanupOutcome.Complete -> Unit
        is SeccompCleanupOutcome.Incomplete -> {
            if (failures.any { it is InterruptedException || it is ClosedByInterruptException }) Thread.currentThread().interrupt()
            failures.filterIsInstance<CancellationException>().firstOrNull()?.let { cancellation ->
                failures.filter { it !== cancellation && it !in cancellation.suppressed }.forEach(cancellation::addSuppressed)
                throw cancellation
            }
            val failure = IllegalStateException(message)
            failures.forEach(failure::addSuppressed)
            throw failure
        }
    }
}
