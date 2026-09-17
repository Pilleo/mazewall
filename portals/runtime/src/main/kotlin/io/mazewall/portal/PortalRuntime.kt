@file:Suppress("ForbiddenImport", "SpreadOperator") // Dynamic proxy is the host-side interface boundary.

package io.mazewall.portal

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns generated service bindings and their portal workers.
 *
 * It never loads an implementation class in the host. The worker artifact is
 * resolved and materialized before broker startup, so the child receives only
 * the verified bundle classpath.
 */
public class PortalRuntime private constructor(
    private val config: PortalRuntimeConfig,
) : AutoCloseable {
    init {
        config.validateProfiles()
    }

    private val registry = PortalServiceRegistry(config.manifests)
    private val brokers = ConcurrentHashMap<String, ProcessBroker>()
    private val proxies = ConcurrentHashMap<PortalServiceId, Any>()
    private val statefulGate = PortalStatefulInvocationGate()

    @Volatile private var closed: Boolean = false

    public fun <T : Any> get(type: Class<T>): T {
        ensureOpen()
        return get(type, registry.resolve(type).serviceId)
    }

    public fun <T : Any> get(
        type: Class<T>,
        id: PortalServiceId,
    ): T {
        ensureOpen()
        val manifest = registry.resolve(type, id)
        return proxies
            .computeIfAbsent(manifest.serviceId) {
            val broker = brokerFor(manifest)
            val delegate = Portal.create(type, broker)
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
                when (method.name) {
                    "equals" -> if (method.parameterCount == 1) proxy === arguments?.singleOrNull() else null
                    "hashCode" -> if (method.parameterCount == 0) System.identityHashCode(proxy) else null
                    "toString" -> if (method.parameterCount == 0) "PortalProxy(${manifest.serviceId.value})" else null
                    else ->
                        statefulGate.invoke(manifest) {
                            try {
                                method.invoke(delegate, *(arguments ?: emptyArray()))
                            } catch (wrapped: InvocationTargetException) {
                                throw (wrapped.targetException ?: wrapped)
                            }
                        }
                }
            }
        }.let(type::cast)
    }

    /** Returns local group health without contacting or controlling a worker. */
    public fun health(): List<PortalServiceHealth> =
        config.manifests
            .groupBy { it.isolationGroup }
            .toSortedMap()
            .map { (group, members) ->
                val baseline = statefulGate.health(members.first())
                val manifest = members.first()
                val broker = brokers[group]
                baseline.copy(
                    policyProfile = manifest.policyProfile,
                    workerProfile = config.profile(manifest),
                    cgroupUsage = broker?.cgroupUsage().orEmpty(),
                    lastWorkerLifecycle = broker?.lastWorkerLifecycle() ?: PortalWorkerLifecycle.notStarted(),
                    workerPids = broker?.workerPids().orEmpty(),
                    lastInvocation = broker?.lastInvocationTiming(),
                )
            }

    private fun ensureOpen() {
        if (closed) throw PortalRuntimeClosedException()
    }

    @Suppress("TooGenericExceptionCaught") // Startup rollback must close a partially started broker for every failure.
    private fun brokerFor(manifest: PortalServiceManifest): ProcessBroker =
        brokers.computeIfAbsent(manifest.isolationGroup) {
            val broker =
                ProcessBroker(
                    workerClasspath = config.workerClasspath(manifest),
                    workerProfile = config.profile(manifest),
                    cgroupRoot = config.cgroupRoot,
                    grantRoots = config.grantRoots,
                )
            try {
                broker.start()
                broker
            } catch (failure: Throwable) {
                runCatching { broker.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw PortalWorkerStartupException(manifest.serviceId, failure)
            }
        }

    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
            proxies.clear()
            var failure: Throwable? = null
            brokers.values.toSet().forEach { broker ->
                runCatching { broker.close() }.exceptionOrNull()?.let { closeFailure ->
                    if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
                }
            }
            brokers.clear()
            failure?.let { throw PortalIsolationException("portal runtime failed to close every broker", it) }
        }
    }

    public companion object {
        @JvmStatic
        public fun open(config: PortalRuntimeConfig = PortalRuntimeConfig.load()): PortalRuntime = PortalRuntime(config)
    }
}
