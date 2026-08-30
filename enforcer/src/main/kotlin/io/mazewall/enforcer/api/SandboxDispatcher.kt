package io.mazewall.enforcer.api

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.Policy
import io.mazewall.PolicyDefinition
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A functional router that executes blocks of code inside policy-specific sandboxes.
 *
 * It automatically caches and reuses [ExecutorService] instances based on the exact
 * [PolicyDefinition]. This prevents thread-explosion while ensuring strict containment.
 *
 * For coroutine support (e.g., `executeSuspend`), ensure `kotlinx-coroutines-core`
 * is on your classpath and use the extensions in `io.mazewall.enforcer.SandboxDispatcherCoroutines`.
 */
object SandboxDispatcher {

    /**
     * INVARIANT (issue-20260826-102609): the cache key is the PROGRAM-RELEVANT PROJECTION of a
     * [PolicyDefinition] — default action, syscall actions, and arg-inspection flags. Landlock
     * filesystem paths must NOT participate. This mirrors the invariant in `PolicyCompilationCache`.
     */
    private data class CacheKey(
        val defaultAction: io.mazewall.core.SeccompAction,
        val syscallActions: Map<io.mazewall.core.Syscall, io.mazewall.core.SeccompAction>,
        val allowMmapExec: Boolean,
        val allowNonThreadClone: Boolean,
        val allowUnsafePrctl: Boolean,
        val lockIntelCet: Boolean,
        val arch: io.mazewall.core.Arch
    ) {
        constructor(definition: PolicyDefinition<*>, arch: io.mazewall.core.Arch) : this(
            definition.defaultAction,
            definition.syscallActions,
            definition.allowMmapExec,
            definition.allowNonThreadClone,
            definition.allowUnsafePrctl,
            definition.lockIntelCet,
            arch
        )
    }

    /** Belt-and-braces cap for the dispatcher cache. */
    private const val MAX_ENTRIES = 32

    /**
     * Cache mapping a distinct Policy projection to its dedicated thread pool.
     * Executor threads are permanently contained and therefore pooled forever by design;
     * eviction only happens under cap pressure or shutdownAll().
     */
    private val poolCache = object : java.util.LinkedHashMap<CacheKey, ExecutorService>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, ExecutorService>): Boolean {
            if (size > MAX_ENTRIES) {
                eldest.value.shutdown()
                return true
            }
            return false
        }
    }

    /**
     * Executes the given [block] on a thread pool perfectly constrained by the [policy].
     * Blocks the calling thread until the execution completes.
     *
     * This method is designed to be easily usable from both Java and Kotlin.
     */
    @JvmStatic
    fun <T> execute(policy: Policy<*, *>, block: Callable<T>): T {
        val definition = policy.definition
        val executor = getOrCreateElasticPool(definition)
        return executor.submit(block).get()
    }

    /**
     * Executes the given Kotlin lambda [block] on a thread pool perfectly constrained by the [policy].
     * Blocks the calling thread until the execution completes.
     */
    inline fun <T> executeBlock(policy: Policy<*, *>, crossinline block: () -> T): T {
        return execute(policy, Callable { block() })
    }

    /**
     * Retrieves or creates the elastic thread pool for the given policy definition.
     */
    @PublishedApi
    internal fun getOrCreateElasticPool(definition: PolicyDefinition<*>): ExecutorService {
        val key = CacheKey(definition, io.mazewall.core.Arch.current())
        synchronized(poolCache) {
            val existing = poolCache[key]
            if (existing != null) return existing

            // Use a cached thread pool to allow elastic scaling for blocking I/O workloads,
            // similar to Dispatchers.IO. Threads idle for 60 seconds are terminated.
            val rawPool = Executors.newCachedThreadPool { runnable ->
                val thread = Thread(runnable)
                thread.isDaemon = true
                thread.name = "mazewall-sandbox-${definition.hashCode().toUInt().toString(16)}"
                thread
            }
            
            // Wrap the raw pool to ensure the policy is applied to every thread created by it.
            // ContainedExecutors.wrap normally takes vararg Policy<*, Uncompiled>.
            // We use the internal installOnCurrentThread to wrap execution directly.
            val wrapped = io.mazewall.enforcer.internal.ContainedExecutorWrapper(rawPool, definition)
            poolCache[key] = wrapped
            return wrapped
        }
    }

    /**
     * Retrieves the current number of cached executors. Used for testing.
     */
    internal fun entryCount(): Int = synchronized(poolCache) { poolCache.size }

    /**
     * Shuts down all cached executors. Useful for application graceful shutdown.
     */
    @JvmStatic
    fun shutdownAll() {
        synchronized(poolCache) {
            poolCache.values.forEach { it.shutdown() }
            poolCache.clear()
        }
    }
}
