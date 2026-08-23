package io.mazewall.profiler.iterative

import io.mazewall.Policy
import io.mazewall.Uncompiled
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.landlock.Landlock

public interface IterativeTaskExecutor {
    fun executeTask(
        currentPolicy: Policy<*, Uncompiled>,
        task: Runnable,
    ): Throwable?
}

public class RealIterativeTaskExecutor(
    /**
     * Upper bound for one profiling iteration. A workload that deadlocks under containment must
     * not hang the profiler forever (issue-20260823-172000); the timeout surfaces as a distinct
     * [IterativeTaskTimeoutException].
     */
    public val iterationTimeoutMs: Long = DEFAULT_ITERATION_TIMEOUT_MS,
) : IterativeTaskExecutor {
    public constructor() : this(DEFAULT_ITERATION_TIMEOUT_MS)

    private val taskCounter = java.util.concurrent.atomic.AtomicLong()

    /** Thrown when a contained iteration exceeds [iterationTimeoutMs]. */
    public class IterativeTaskTimeoutException(timeoutMs: Long) :
        IllegalStateException("Profiling iteration exceeded ${timeoutMs}ms and was interrupted")

    override fun executeTask(
        currentPolicy: Policy<*, Uncompiled>,
        task: Runnable,
    ): Throwable? {
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
    }
}
