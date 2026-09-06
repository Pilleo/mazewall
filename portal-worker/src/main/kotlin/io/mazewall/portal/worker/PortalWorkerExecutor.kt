package io.mazewall.portal.worker

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Prestarted, bounded request executor created before worker Seccomp installation. */
internal object PortalWorkerExecutor {
    fun create(concurrency: Int): ThreadPoolExecutor {
        require(concurrency >= 1) { "portal worker concurrency must be >= 1" }
        return ThreadPoolExecutor(
            concurrency,
            concurrency,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(concurrency),
            ThreadPoolExecutor.AbortPolicy(),
        ).also { it.prestartAllCoreThreads() }
    }
}
