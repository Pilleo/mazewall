package io.mazewall.enforcer

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

    // Cache mapping a distinct Policy definition to its dedicated thread pool.
    // PolicyDefinition is a data class, so it works perfectly as a map key.
    private val poolCache = ConcurrentHashMap<PolicyDefinition<*>, ExecutorService>()

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
        return poolCache.computeIfAbsent(definition) { def ->
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
            io.mazewall.enforcer.internal.ContainedExecutorWrapper(rawPool, def)
        }
    }

    /**
     * Shuts down all cached executors. Useful for application graceful shutdown.
     */
    @JvmStatic
    fun shutdownAll() {
        poolCache.values.forEach { it.shutdown() }
        poolCache.clear()
    }
}
