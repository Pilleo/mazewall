package io.mazewall.profiler.iterative

import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.Uncompiled
import io.mazewall.landlock.Landlock

internal interface IterativeTaskExecutor {
    fun executeTask(
        currentPolicy: Policy<*, Uncompiled>,
        task: Runnable,
    ): Throwable?
}

internal class RealIterativeTaskExecutor internal constructor(
    /**
     * Upper bound for one profiling iteration. A workload that deadlocks under containment must
     * not hang the profiler forever (issue-20260823-172000); the timeout surfaces as a distinct
     * [IterativeTaskTimeoutException].
     */
    public val iterationTimeoutMs: Long,
    private val clockPreloader: () -> Unit,
) : IterativeTaskExecutor {
    public constructor() : this(DEFAULT_ITERATION_TIMEOUT_MS, ::preloadNativeClockWrappers)

    public constructor(iterationTimeoutMs: Long) : this(iterationTimeoutMs, ::preloadNativeClockWrappers)

    private val taskCounter = java.util.concurrent.atomic
        .AtomicLong()

    /** Thrown when a contained iteration exceeds [iterationTimeoutMs]. */
    class IterativeTaskTimeoutException(
        timeoutMs: Long,
    ) : IllegalStateException("Profiling iteration exceeded ${timeoutMs}ms and was interrupted")

    override fun executeTask(
        currentPolicy: Policy<*, Uncompiled>,
        task: Runnable,
    ): Throwable? {
        // First-use HotSpot wrappers can allocate executable CodeHeap memory. Prime the
        // clock wrappers before the fresh worker installs its restrictive policy.
        clockPreloader()
        var error: Throwable? = null
        // NOTE: A fresh thread per iteration is REQUIRED, not incidental: seccomp filters are
        // permanent for the OS thread's lifetime, so a less-restrictive next iteration can never
        // run on an already-contained thread. Diagnostics context is therefore carried by naming.
        val thread =
            Thread(null, {
                // Ensure Landlock is active even for empty policies to force discovery
                if (currentPolicy.allowedFsReadPaths.isEmpty() && currentPolicy.allowedFsWritePaths.isEmpty()) {
                    Landlock.applyRestrictiveBarrier()
                }
                ContainedExecutors.installOnCurrentThread(currentPolicy)
                task.run()
            }, "iterative-profiler-task-${taskCounter.incrementAndGet()}")
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
            error = e
        }
        thread.start()
        thread.join(iterationTimeoutMs)
        if (thread.isAlive) {
            // Escalate: interrupt so blocking I/O can unwind. A JVM thread cannot be forcibly
            // stopped (Thread.stop throws on modern JDKs); if it is still alive after the grace
            // period we report the timeout and leave the OS thread to die with its process.
            thread.interrupt()
            thread.join(GRACE_PERIOD_MS)
            check(!thread.isAlive) {
                "Profiling iteration ignored interruption for ${GRACE_PERIOD_MS}ms after " +
                    "exceeding ${iterationTimeoutMs}ms; runaway tracee thread '${thread.name}'"
            }
            error = error ?: IterativeTaskTimeoutException(iterationTimeoutMs)
        }
        return error
    }

    public companion object {
        public const val DEFAULT_ITERATION_TIMEOUT_MS: Long = 120_000L
        private const val GRACE_PERIOD_MS: Long = 5_000L

        private fun preloadNativeClockWrappers() {
            System.currentTimeMillis()
            System.nanoTime()
        }
    }
}
