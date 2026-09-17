package io.mazewall.enforcer.api

import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A functional router that executes blocks of code inside policy-specific sandboxes.
 *
 * It automatically caches and reuses [ExecutorService] instances based on the exact
 * [PolicyDefinition]. This prevents thread-explosion while ensuring strict containment.
 *
 * For coroutine support (e.g., `executeSuspend`), ensure `kotlinx-coroutines-core`
 * is on your classpath and use the extensions in `io.mazewall.enforcer.SandboxDispatcherCoroutines`.
 */
public object SandboxDispatcher : AutoCloseable {
    internal const val MAX_CACHED_POOLS: Int = 32
    private const val HEX_RADIX: Int = 16
    private const val POOL_SHUTDOWN_WAIT_SECONDS: Long = 5
    private const val POOL_FORCE_STOP_WAIT_SECONDS: Long = 1

    // Java's access-ordered LinkedHashMap constructor requires an explicit load factor.
    private val cacheLoadFactor: Float = java.lang.Float.parseFloat("0.75")

    /**
     * Cache key for the sandbox dispatcher pools.
     * While BPF compilation cache correctly ignores Landlock paths (since paths do not change
     * the compiled syscall filter instructions), the *Executor Cache* must include Landlock paths
     * in its identity because `ContainedExecutorWrapper` is initialized once per pool with a specific
     * `PolicyDefinition`. Reusing an executor for a different path policy would incorrectly enforce
     * the initial Landlock paths on subsequent tasks.
     */
    private data class CacheKey(
        val defaultAction: io.mazewall.platform.syscall.SeccompAction,
        val syscallActions: Map<io.mazewall.platform.syscall.Syscall, io.mazewall.platform.syscall.SeccompAction>,
        val allowMmapExec: Boolean,
        val allowNonThreadClone: Boolean,
        val allowUnsafePrctl: Boolean,
        val lockIntelCet: Boolean,
        val allowedFsReadPaths: Set<io.mazewall.platform.lifecycle.SandboxedPath>,
        val allowedFsWritePaths: Set<io.mazewall.platform.lifecycle.SandboxedPath>,
        val enforceLandlock: Boolean,
        val arch: io.mazewall.platform.syscall.Arch,
    ) {
        constructor(definition: PolicyDefinition<*>, arch: io.mazewall.platform.syscall.Arch) : this(
            definition.defaultAction,
            definition.syscallActions,
            definition.allowMmapExec,
            definition.allowNonThreadClone,
            definition.allowUnsafePrctl,
            definition.lockIntelCet,
            definition.allowedFsReadPaths,
            definition.allowedFsWritePaths,
            definition.enforceLandlock,
            arch,
        )
    }

    /**
     * Cache mapping a distinct Policy projection to its dedicated thread pool.
     * Executor threads are permanently contained and therefore pooled forever by design;
     * eviction only happens under cap pressure or shutdownAll().
     */
    private val poolCache = java.util.LinkedHashMap<CacheKey, ExecutorService>(MAX_CACHED_POOLS, cacheLoadFactor, true)

    /**
     * Executes the given [block] on a thread pool perfectly constrained by the [policy].
     * Blocks the calling thread until the execution completes.
     *
     * This method is designed to be easily usable from both Java and Kotlin.
     */
    @JvmStatic
    public fun <T> execute(
        policy: Policy<*, *>,
        block: Callable<T>,
    ): T {
        val definition = policy.definition
        val executor = getOrCreateElasticPool(definition)
        return executor.submit(block).get()
    }

    /**
     * Executes the given Kotlin lambda [block] on a thread pool perfectly constrained by the [policy].
     * Blocks the calling thread until the execution completes.
     */
    public inline fun <T> executeBlock(
        policy: Policy<*, *>,
        crossinline block: () -> T,
    ): T {
        return execute(policy, Callable { block() })
    }

    /**
     * Retrieves or creates the elastic thread pool for the given policy definition.
     */
    @PublishedApi
    internal fun getOrCreateElasticPool(definition: PolicyDefinition<*>): ExecutorService {
        val key = CacheKey(
            definition,
            io.mazewall.platform.syscall.Arch
            .current(),
        )
        var evicted: ExecutorService? = null
        val wrapped = synchronized(poolCache) {
            val existing = poolCache[key]
            if (existing != null) return existing

            // Use a cached thread pool to allow elastic scaling for blocking I/O workloads,
            // similar to Dispatchers.IO. Threads idle for 60 seconds are terminated.
            val rawPool = Executors.newCachedThreadPool { runnable ->
                val thread = Thread(runnable)
                thread.isDaemon = true
                thread.name = "mazewall-sandbox-${definition.hashCode().toUInt().toString(HEX_RADIX)}"
                thread
            }

            // Wrap the raw pool to ensure the policy is applied to every thread created by it.
            // ContainedExecutors.wrap normally takes vararg Policy<*, Uncompiled>.
            // We use the internal installOnCurrentThread to wrap execution directly.
            val created = io.mazewall.enforcer.internal
                .ContainedExecutorWrapper(rawPool, definition)
            poolCache[key] = created
            if (poolCache.size > MAX_CACHED_POOLS) {
                val eldestKey = poolCache.keys.first()
                evicted = poolCache.remove(eldestKey)
            }
            created
        }
        evicted?.let(::retirePool)
        return wrapped
    }

    /**
     * Retrieves the current number of cached executors. Used for testing.
     */
    internal fun entryCount(): Int = synchronized(poolCache) { poolCache.size }

    internal fun cachedPoolsForTest(): List<ExecutorService> = synchronized(poolCache) { poolCache.values.toList() }

    /**
     * Shuts down all cached executors. Useful for application graceful shutdown.
     */
    @JvmStatic
    public fun shutdownAll() {
        val pools = synchronized(poolCache) {
            poolCache.values.toList().also { poolCache.clear() }
        }
        pools.forEach(::retirePool)
    }

    private fun retirePool(pool: ExecutorService) {
        pool.shutdown()
        try {
            if (!pool.awaitTermination(POOL_SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                pool.shutdownNow()
                pool.awaitTermination(POOL_FORCE_STOP_WAIT_SECONDS, TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            pool.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        shutdownAll()
    }
}
