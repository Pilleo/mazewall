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

public object RealIterativeTaskExecutor : IterativeTaskExecutor {
    private val taskCounter = java.util.concurrent.atomic.AtomicLong()

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
        thread.join()
        return error
    }
}
