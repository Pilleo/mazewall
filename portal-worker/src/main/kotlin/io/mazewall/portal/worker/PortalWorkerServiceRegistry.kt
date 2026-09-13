package io.mazewall.portal.worker

import io.mazewall.portal.PortalServiceManifest

/**
 * Owns worker-local service graphs keyed by isolation group.
 *
 * Factories and their return values never leave the worker. A group is built
 * once, atomically, and all members must agree on its launch shape.
 */
public class PortalWorkerServiceRegistry(
    manifests: List<PortalServiceManifest>,
    private val factories: Map<String, PortalWorkerFactory>,
    private val loader: ClassLoader = PortalWorkerServiceRegistry::class.java.classLoader,
) {
    private val manifestsByGroup: Map<String, List<PortalServiceManifest>> =
        manifests.groupBy { it.isolationGroup }.mapValues { (_, members) -> members.sortedBy { it.serviceId.value } }
    private val instancesByGroup = mutableMapOf<String, Map<String, Any>>()

    init {
        manifestsByGroup.forEach { (group, members) ->
            val shapes = members.map { Triple(it.workerArtifact, it.policyProfile, it.instanceMode) }.toSet()
            require(shapes.size == 1) { "portal worker isolation group $group has incompatible launch metadata" }
            members.forEach { manifest ->
                require(manifest.implementationName in factories) {
                    "portal worker service ${manifest.serviceId.value} has no worker factory for ${manifest.implementationName}"
                }
            }
        }
    }

    /** Builds and returns the immutable worker-local graph for [group]. */
    @Synchronized
    public fun group(group: String): Map<String, Any> =
        instancesByGroup.getOrPut(group) {
            val members = manifestsByGroup[group] ?: throw IllegalArgumentException("unknown portal isolation group: $group")
            val context = PortalWorkerConstructionContext(loader)
            java.util.Collections.unmodifiableMap(
                members.associate { manifest ->
                    manifest.serviceId.value to factories.getValue(manifest.implementationName).create(manifest, context)
                },
            )
        }
}
