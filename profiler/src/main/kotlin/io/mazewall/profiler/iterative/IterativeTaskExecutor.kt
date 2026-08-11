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
    override fun executeTask(
        currentPolicy: Policy<*, Uncompiled>,
        task: Runnable,
    ): Throwable? {
        var error: Throwable? = null
        val thread =
            Thread {
                // Ensure Landlock is active even for empty policies to force discovery
                if (currentPolicy.allowedFsReadPaths.isEmpty() && currentPolicy.allowedFsWritePaths.isEmpty()) {
                    Landlock.applyRestrictiveBarrier()
                }
                ContainedExecutors.installOnCurrentThread(currentPolicy)
                task.run()
            }
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
            error = e
        }
        thread.start()
        thread.join()
        return error
    }
}
