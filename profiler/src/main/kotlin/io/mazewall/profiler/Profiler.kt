package io.mazewall.profiler

import io.mazewall.Policy
import io.mazewall.Uncompiled
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * High-level API for system call profiling and Bill of Behavior (SBoB) generation.
 *
 * Tier S Profiler: Uses seccomp USER_NOTIF to trap system calls and notify a background daemon,
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
object Profiler {
    private val defaultService = AtomicReference<ProfilerService>()

    private fun service(): ProfilerService {
        defaultService.get()?.let { return it }
        val created = ProfilerService(ProfilerRuntime.default())
        return if (defaultService.compareAndSet(null, created)) created else defaultService.get()
    }

    /** Profiles [block] on a dedicated platform thread. */
    @JvmStatic
    @JvmOverloads
    fun <T> profile(
        processWide: Boolean = false,
        captureStackTraces: Boolean = true,
        block: () -> T,
    ): ProfilingResult<T> = service().profile(processWide, captureStackTraces, block)

    /** Java-friendly overload for profiling a [Callable]. */
    @JvmStatic
    @JvmOverloads
    fun <T> profile(
        callable: Callable<T>,
        processWide: Boolean = false,
        captureStackTraces: Boolean = true,
    ): ProfilingResult<T> = profile(processWide, captureStackTraces) { callable.call() }

    /** Java-friendly overload for profiling a [Runnable]. */
    @JvmStatic
    @JvmOverloads
    fun profile(
        runnable: Runnable,
        processWide: Boolean = false,
        captureStackTraces: Boolean = true,
    ): ProfilingResult<Unit> = profile(processWide, captureStackTraces) { runnable.run() }

    @JvmStatic
    fun wrap(
        delegate: ExecutorService,
        vararg policies: Policy<*, Uncompiled>,
    ): ProfilerExecutorWrapper = wrap(delegate, true, *policies)

    @JvmStatic
    fun wrap(
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
    fun shutdown() {
        defaultService.get()?.shutdown()
    }

    class ProfilerExecutorWrapper internal constructor(
        private val delegate: ExecutorService,
        private val session: ProfilerSession,
    ) : ExecutorService by delegate {
        val recentLogs = session.logs
        val recentStackProfiles = session.stackProfiles

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

        /** Compiles a [BillOfBehavior] from the events captured so far. */
        fun compileBillOfBehavior(): BillOfBehavior = session.compileBillOfBehavior()
    }
}
