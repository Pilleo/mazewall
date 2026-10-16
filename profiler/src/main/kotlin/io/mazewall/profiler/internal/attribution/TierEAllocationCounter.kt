package io.mazewall.profiler.internal.attribution

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory

/** Benchmark-only source of the bytes allocated by the calling collector thread. */
internal fun interface TierEAllocationCounter {
    fun currentThreadAllocatedBytes(): Long
}

/** Resolves JVM allocation accounting lazily so ordinary Tier E sessions never touch MXBean APIs. */
internal object TierEAllocationCounters {
    const val UNAVAILABLE = -1L

    private val threadMxBean: ThreadMXBean? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ManagementFactory.getThreadMXBean() as? ThreadMXBean
    }

    fun currentThreadAllocatedBytes(): Long {
        val bean = threadMxBean ?: return UNAVAILABLE
        if (!bean.isThreadAllocatedMemorySupported) return UNAVAILABLE
        if (!bean.isThreadAllocatedMemoryEnabled) {
            try {
                bean.isThreadAllocatedMemoryEnabled = true
            } catch (_: SecurityException) {
                return UNAVAILABLE
            } catch (_: UnsupportedOperationException) {
                return UNAVAILABLE
            }
        }
        return bean.getThreadAllocatedBytes(Thread.currentThread().threadId()).takeIf { it >= 0L } ?: UNAVAILABLE
    }
}
