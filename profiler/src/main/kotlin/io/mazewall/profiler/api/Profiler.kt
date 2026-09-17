package io.mazewall.profiler.api

import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.Uncompiled
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * USER_NOTIF (Tier S) session installer and daemon client. Application lambdas use [MazewallProfiler].
 *
 * Traps system calls and notifies a background daemon,
 * which resolves arguments (like paths) using `process_vm_readv` before allowing the syscall to continue.
 *
 * ### ⚠️ Security Warning & TOCTOU Limitations:
 * USER_NOTIF-based profiling is inherently subject to **Time-of-Check to Time-of-Use (TOCTOU)** security races
 * when resolving pointer-based system call arguments (such as file paths). A malicious or concurrent thread in the
 * target process can overwrite the path string memory after the profiler daemon reads it via `process_vm_readv`
 * but before the kernel actually dereferences and executes the system call.
 *
 * For robust, race-free, and kernel-enforced filesystem containment, **Landlock LSM** is the preferred and recommended
 * mechanism, as it evaluates and enforces path-based restrictions directly in the kernel space at the inode level,
 * making it completely immune to pointer-dereferencing TOCTOU attacks.
 */
public object Profiler {
    private val defaultService = AtomicReference<ProfilerService>()

    private fun service(): ProfilerService {
        defaultService.get()?.let { return it }
        val created = ProfilerService(ProfilerRuntime.default())
        return if (defaultService.compareAndSet(null, created)) created else defaultService.get()
    }

    /** Profiles [block] on a dedicated platform thread. */
    @JvmStatic
    @JvmOverloads
    public fun <T> profile(
        processWide: Boolean = false,
        captureStackTraces: Boolean = true,
        block: () -> T,
    ): ProfilingResult<T> = service().profile(processWide, captureStackTraces, block)

    /** Benchmark-only bounded execution path; ordinary callers retain [profile]'s default limits. */
    internal fun <T> profileForDiagnostics(
        processWide: Boolean = false,
        captureStackTraces: Boolean = true,
        limits: ProfilerExecutionLimits,
        block: () -> T,
    ): ProfilingResult<T> = service().profile(processWide, captureStackTraces, limits, block)

    /** Java-friendly overload for profiling a [Callable]. */
    @JvmStatic
    @JvmOverloads
    public fun <T> profile(
        callable: Callable<T>,
        processWide: Boolean = false,
        captureStackTraces: Boolean = true,
    ): ProfilingResult<T> = profile(processWide, captureStackTraces) { callable.call() }

    /** Java-friendly overload for profiling a [Runnable]. */
    @JvmStatic
    @JvmOverloads
    public fun profile(
        runnable: Runnable,
        processWide: Boolean = false,
        captureStackTraces: Boolean = true,
    ): ProfilingResult<Unit> = profile(processWide, captureStackTraces) { runnable.run() }

    @JvmStatic
    public fun wrap(
        delegate: ExecutorService,
        vararg policies: Policy<*, Uncompiled>,
    ): ProfilerExecutorWrapper = wrap(delegate, true, *policies)

    @JvmStatic
    public fun wrap(
        delegate: ExecutorService,
        captureStackTraces: Boolean,
        vararg policies: Policy<*, Uncompiled>,
    ): ProfilerExecutorWrapper = service().wrap(delegate, captureStackTraces, *policies)

    /**
     * Drains the default service's active sessions into pass-through mode.
     * The daemon stays alive to continue notifications from threads with permanent filters.
     * Subsequent profile/wrap calls may create new sessions.
     */
    @JvmStatic
    public fun shutdown() {
        defaultService.get()?.shutdown()
    }

    public class ProfilerExecutorWrapper internal constructor(
        private val delegate: ExecutorService,
        private val session: ProfilerSession,
    ) : ExecutorService by delegate,
        AutoCloseable {
        public val recentLogs: CopyOnWriteArrayList<TraceEvent> = session.logs
        public val recentStackProfiles: Map<TraceEvent, List<Array<StackTraceElement>>> = session.stackProfiles

        override fun close() {
            shutdown()
        }

        override fun <T : Any?> submit(task: Callable<T>): Future<T> =
            delegate.submit(
                Callable {
                session.installOnCurrentThread()
                task.call()
            },
            )

        override fun submit(task: Runnable): Future<*> =
            delegate.submit(
                Runnable {
                session.installOnCurrentThread()
                task.run()
            },
            )

        override fun execute(command: Runnable) {
            delegate.execute {
                session.installOnCurrentThread()
                command.run()
            }
        }

        override fun shutdownNow(): List<Runnable> {
            val pending = delegate.shutdownNow()
            // Executor shutdown is the ownership boundary for the profiling session too.
            // Leaving the session registered keeps its daemon listener (and output pump)
            // alive after callers have discarded the wrapper, which can stall test/JVM
            // shutdown and leak a permanent notification path.
            session.closeWithResult().throwIfIncomplete("Profiler executor session cleanup failed")
            return pending
        }

        override fun shutdown() {
            delegate.shutdown()
            session.closeWithResult().throwIfIncomplete("Profiler executor session cleanup failed")
        }

        /** Compiles a [BillOfBehavior] from the events captured so far. */
        public fun compileBillOfBehavior(): BillOfBehavior = session.compileBillOfBehavior()

        /**
         * Immutable behavior and collector-coverage snapshot. Drain this executor and call
         * [Profiler.shutdown] before using the snapshot for enforcement; active collection
         * remains incomplete. Shutting down an unused wrapper yields an empty, drained result.
         */
        public fun snapshot(): ProfilingResult<Unit> = session.result(Unit)
    }
}
