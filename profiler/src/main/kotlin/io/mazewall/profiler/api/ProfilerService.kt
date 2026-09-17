package io.mazewall.profiler.api

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.PolicyPresets
import io.mazewall.enforcer.api.Uncompiled
import io.mazewall.enforcer.diagnostics.validateNotVirtual
import io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome
import io.mazewall.profiler.ffi.ProfilerNativeCommandWriter
import io.mazewall.profiler.internal.TraceListenerMachine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference

/** Explicitly bounded worker execution and post-closure grace periods. */
internal data class ProfilerExecutionLimits(
    val workerTimeoutMillis: Long = DEFAULT_WORKER_TIMEOUT_MILLIS,
    val workerGraceMillis: Long = DEFAULT_WORKER_GRACE_MILLIS,
) {
    init {
        require(workerTimeoutMillis > 0) { "workerTimeoutMillis must be positive" }
        require(workerGraceMillis > 0) { "workerGraceMillis must be positive" }
    }

    internal companion object {
        const val DEFAULT_WORKER_TIMEOUT_MILLIS = 60_000L
        const val DEFAULT_WORKER_GRACE_MILLIS = 5_000L
        val DEFAULT = ProfilerExecutionLimits()
    }
}

/** Owns the dependencies and active sessions of one independently usable profiler runtime. */
internal class ProfilerService(
    private val runtime: ProfilerRuntime,
) {
    private val sessions = ConcurrentHashMap.newKeySet<ProfilerSession>()
    private val cleanupLock = Any()

    fun openSession(
        policy: PolicyDefinition<*> = PolicyPresets.PURE_COMPUTE_UNSAFE,
        processWide: Boolean = false,
        captureStackTraces: Boolean = true,
    ): ProfilerSession {
        ProfilerNativeCommandWriter.preload()
        TraceListenerMachine.preload()
        ProfilerAckPreload.ensureLoaded()
        val context = runtime.daemonManager().getOrSpawnSharedDaemon()
        return ProfilerSession(runtime, context, policy, processWide, captureStackTraces) { session ->
            if (session.cleanupOutcome == SeccompCleanupOutcome.Complete) sessions.remove(session)
        }.also(sessions::add)
    }

    @Suppress("TooGenericExceptionCaught") // Transfer every workload failure across the dedicated worker boundary.
    fun <T> profile(
        processWide: Boolean = false,
        captureStackTraces: Boolean = true,
        block: () -> T,
    ): ProfilingResult<T> = profile(processWide, captureStackTraces, ProfilerExecutionLimits.DEFAULT, block)

    @Suppress("TooGenericExceptionCaught") // Transfer every workload failure across the dedicated worker boundary.
    fun <T> profile(
        processWide: Boolean = false,
        captureStackTraces: Boolean = true,
        limits: ProfilerExecutionLimits,
        block: () -> T,
    ): ProfilingResult<T> {
        validateNotVirtual()
        return openSession(processWide = processWide, captureStackTraces = captureStackTraces).use { session ->
            val result = AtomicReference<T>()
            val failure = AtomicReference<Throwable>()
            val worker = Thread {
                try {
                    session.installOnCurrentThread()
                    result.set(block())
                } catch (workerFailure: Exception) {
                    failure.set(workerFailure)
                } catch (workerFailure: Error) {
                    failure.set(workerFailure)
                }
            }.apply {
                name = "mazewall-profiler-worker"
                start()
            }
            worker.join(limits.workerTimeoutMillis)
            if (worker.isAlive) {
                val deadline = IllegalStateException(
                    "Profiler worker '${worker.name}' did not terminate within ${limits.workerTimeoutMillis}ms",
                )
                val cleanup = session.closeWithResult()
                if (cleanup is SeccompCleanupOutcome.Incomplete) {
                    deadline.addSuppressed(IllegalStateException("Profiler session cleanup was incomplete at worker deadline"))
                }
                worker.interrupt()
                worker.join(limits.workerGraceMillis)
                failure.get()?.let(deadline::addSuppressed)
                if (worker.isAlive) {
                    deadline.addSuppressed(
                        IllegalStateException(
                            "Profiler worker '${worker.name}' remained alive after ${limits.workerGraceMillis}ms grace",
                        ),
                    )
                }
                throw deadline
            }
            failure.get()?.let { throw it }
            session.close()
            session.result(result.get())
        }
    }

    fun wrap(
        delegate: ExecutorService,
        captureStackTraces: Boolean = true,
        vararg policies: Policy<*, Uncompiled>,
    ): Profiler.ProfilerExecutorWrapper {
        val policy = if (policies.isEmpty()) PolicyPresets.PURE_COMPUTE_UNSAFE else PolicyDefinition.combine(policies = policies.map { it.definition }.toTypedArray())
        return Profiler.ProfilerExecutorWrapper(delegate, openSession(policy, captureStackTraces = captureStackTraces))
    }

    /** Closing sessions leaves the daemon serving permanent kernel filters in pass-through mode. */
    fun shutdown() {
        synchronized(cleanupLock) {
            val closing = sessions.toList()
            try {
                closing.forEach { it.closeWithResult() }
            } finally {
                // A session publishes its aborted outcome before the same fatal error escapes.
                cleanupOutcome = profilerCleanupOutcome(
                    closing.map { it.cleanupOutcome },
                    closing.flatMap { it.retryableOwnership() },
                )
            }
            cleanupOutcome.throwIfIncomplete("Profiler service cleanup failed")
        }
    }

    @Volatile
    var cleanupOutcome: SeccompCleanupOutcome = SeccompCleanupOutcome.Complete
        private set
}
