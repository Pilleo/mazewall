package io.mazewall.enforcer.api

import io.mazewall.Policy
import io.mazewall.PolicyDefinition
import io.mazewall.enforcer.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.state.*
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A functional router that executes blocks of code inside policy-specific sandboxes.
 *
 * It caches a bounded number of [ExecutorService] instances based on the exact
 * [PolicyDefinition]. The full definition is intentional: executor threads permanently acquire
 * both Seccomp and Landlock restrictions, so policies with different filesystem rules cannot
 * safely share a worker pool. Least-recently-used pools are shut down when the cache is full.
 *
 * For coroutine support (e.g., `executeSuspend`), ensure `kotlinx-coroutines-core`
 * is on your classpath and use the extensions in `io.mazewall.enforcer.SandboxDispatcherCoroutines`.
 */
object SandboxDispatcher {
    internal const val MAX_CACHED_POOLS: Int = 32

    /**
     * Access-ordered cache guarded by itself. Shutting down an evicted pool prevents permanently
     * contained daemon threads from accumulating when callers construct dynamic policies.
     */
    private val poolCache = object : LinkedHashMap<PolicyDefinition<*>, ExecutorService>(
        MAX_CACHED_POOLS,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PolicyDefinition<*>, ExecutorService>): Boolean {
            val shouldEvict = size > MAX_CACHED_POOLS
            if (shouldEvict) eldest.value.shutdown()
            return shouldEvict
        }
    }

    /**
     * Executes the given [block] on a thread pool perfectly constrained by the [policy].
     * Blocks the calling thread until the execution completes.
     *
     * This method is designed to be easily usable from both Java and Kotlin.
     */
    @JvmStatic
    fun <T> execute(
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
    inline fun <T> executeBlock(
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
        synchronized(poolCache) {
            poolCache[definition]?.let { return it }
            val pool = createElasticPool(definition)
            poolCache[definition] = pool
            return pool
        }
    }

    private fun createElasticPool(definition: PolicyDefinition<*>): ExecutorService {
        return run {
            val def = definition
            // Use a cached thread pool to allow elastic scaling for blocking I/O workloads,
            // similar to Dispatchers.IO. Threads idle for 60 seconds are terminated.
            val rawPool = Executors.newCachedThreadPool { runnable ->
                val thread = Thread(runnable)
                thread.isDaemon = true
                thread.name = "mazewall-sandbox-${def.hashCode().toUInt().toString(16)}"
                thread
            }

            // Wrap the raw pool to ensure the policy is applied to every thread created by it.
            // ContainedExecutors.wrap normally takes vararg Policy<*, Uncompiled>.
            // We use the internal installOnCurrentThread to wrap execution directly.
            io.mazewall.enforcer.internal
                .ContainedExecutorWrapper(rawPool, def)
        }
    }

    internal fun cachedPoolsForTest(): List<ExecutorService> = synchronized(poolCache) { poolCache.values.toList() }

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
