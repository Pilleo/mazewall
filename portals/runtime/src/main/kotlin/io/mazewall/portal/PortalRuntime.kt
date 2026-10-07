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
    private val brokers = PortalBrokerOwnership<ProcessBroker>(DEFAULT_CLOSE_TIMEOUT_MS)
    private val proxies = ConcurrentHashMap<PortalServiceId, Any>()
    private val statefulGate = PortalStatefulInvocationGate()

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
                            ensureOpen()
                            try {
                                method.invoke(delegate, *(arguments ?: emptyArray()))
                            } catch (wrapped: InvocationTargetException) {
                                throw (wrapped.targetException ?: wrapped)
                            }
                        }
                }
            }
        }.let { value ->
            ensureOpen()
            type.cast(value)
        }
    }

    /** Returns local group health without contacting or controlling a worker. */
    public fun health(): List<PortalServiceHealth> =
        config.manifests
            .groupBy { it.isolationGroup }
            .toSortedMap()
            .map { (group, members) ->
                val baseline = statefulGate.health(members.first())
                val manifest = members.first()
                 val broker = brokers.ready(group)
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
        brokers.assertOpen()
    }

    @Suppress("TooGenericExceptionCaught") // Startup rollback must close a partially started broker for every failure.
    private fun brokerFor(manifest: PortalServiceManifest): ProcessBroker = try {
        brokers.acquire(manifest.isolationGroup, {
                ProcessBroker(
                    workerClasspath = config.workerClasspath(manifest),
                    workerProfile = config.profile(manifest),
                    cgroupRoot = config.cgroupRoot,
                    grantRoots = config.grantRoots,
                )
            }) { it.start() }
    } catch (closed: PortalRuntimeClosedException) {
        throw closed
    } catch (fatal: Error) {
        throw fatal
    } catch (failure: Exception) {
        throw PortalWorkerStartupException(manifest.serviceId, failure)
    }

    override fun close() {
        brokers.close()
        proxies.clear()
    }

    public companion object {
        private const val DEFAULT_CLOSE_TIMEOUT_MS = 30_000L

        @JvmStatic
        public fun open(config: PortalRuntimeConfig = PortalRuntimeConfig.load()): PortalRuntime = PortalRuntime(config)
    }
}
