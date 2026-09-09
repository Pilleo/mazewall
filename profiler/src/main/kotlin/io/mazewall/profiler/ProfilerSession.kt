package io.mazewall.profiler

import io.mazewall.PolicyDefinition
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.enforcer.diagnostics.validateNotVirtual
import io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome
import io.mazewall.profiler.compiler.BobCompiler
import io.mazewall.profiler.engine.TraceEvent
import io.mazewall.profiler.internal.DaemonContext
import io.mazewall.profiler.internal.ProfilerTraceListener
import java.nio.channels.ClosedByInterruptException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

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
    private var closed = false

    @Volatile
    var cleanupOutcome: SeccompCleanupOutcome = SeccompCleanupOutcome.Complete
        private set

    @Synchronized
    fun installOnCurrentThread() {
        // Existing wrapped workers retain their kernel filters in pass-through after session close.
        if (closed || Thread.currentThread() in appliedThreads) return
        validateNotVirtual()
        val tid = runtime.tid()
        state.register(tid, Thread.currentThread())
        var installed = false
        try {
            runtime.installer.installProfilingFilterForThread(
                context.socketPath,
                policy,
                logs,
                if (captureStackTraces) stackProfiles else null,
                pathCache,
                processWide,
            ) { fd, _, _, _, ready ->
                val listener = ProfilerTraceListener(
                    FileDescriptor.adopt(fd, FileDescriptorRole.UnixSocket),
                    logs,
                    if (captureStackTraces) stackProfiles else null,
                    pathCache,
                    state::threadFor,
                    runtime.native,
                )
                listeners.add(listener)
                listener.start(ready)
            }
            appliedThreads.add(Thread.currentThread())
            installed = true
        } finally {
            if (!installed) state.remove(tid)
        }
    }

    @Synchronized
    fun closeWithResult(): SeccompCleanupOutcome {
        closed = true
        try {
            val outcomes = listeners.map { it.closeWithResult() }
            val incomplete = outcomes.filterIsInstance<SeccompCleanupOutcome.Incomplete>()
            cleanupOutcome = if (incomplete.isEmpty()) {
                SeccompCleanupOutcome.Complete
            } else {
                SeccompCleanupOutcome.Incomplete(
                incomplete.flatMap { it.failures },
                incomplete.flatMap { it.retryableOwnership },
            )
            }
            return cleanupOutcome
        } finally {
            state.clear()
            appliedThreads.clear()
            onClosed(this)
        }
    }

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
            throw IllegalStateException(message).also { failure -> failures.forEach(failure::addSuppressed) }
        }
    }
}
