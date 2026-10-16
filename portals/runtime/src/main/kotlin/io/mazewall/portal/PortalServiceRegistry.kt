package io.mazewall.portal

/**
 * Validated in-memory view of generated service manifests.
 *
 * A registry is deliberately independent of worker launch mechanics: it can
 * reject ambiguous or internally inconsistent metadata before a child process
 * is started.
 */
public class PortalServiceRegistry(
    manifests: Collection<PortalServiceManifest>,
) {
    private val byId: Map<PortalServiceId, PortalServiceManifest> = manifests.associateBy { it.serviceId }
    private val byContract: Map<String, List<PortalServiceManifest>> =
        manifests.groupBy { it.contractName }.mapValues { (_, values) -> values.sortedBy { it.serviceId.value } }

    init {
        require(byId.size == manifests.size) { "portal service manifests contain duplicate service IDs" }
        manifests
            .groupBy { it.isolationGroup }
            .forEach { (group, members) ->
                val launchShapes = members.map { Triple(it.workerArtifact, it.policyProfile, it.instanceMode) }.toSet()
                require(launchShapes.size == 1) {
                    "portal isolation group $group has incompatible worker artifact, policy profile, or instance mode"
                }
            }
    }

    /** Finds the sole implementation of [type], rejecting an implicit choice. */
    public fun <T : Any> resolve(type: Class<T>): PortalServiceManifest {
        val candidates = byContract[type.name].orEmpty()
        return when (candidates.size) {
            0 -> throw PortalServiceNotFoundException(type.name)
            1 -> candidates.single()
            else -> throw PortalServiceAmbiguousException(type.name, candidates.map { it.serviceId })
        }
    }

    /** Finds an explicitly selected implementation and verifies its contract. */
    public fun <T : Any> resolve(
        type: Class<T>,
        id: PortalServiceId,
    ): PortalServiceManifest {
        val manifest = byId[id] ?: throw PortalServiceNotFoundException(type.name, id)
        require(manifest.contractName == type.name) {
            "portal service ${id.value} implements ${manifest.contractName}, not ${type.name}"
        }
        return manifest
    }

    public fun all(): List<PortalServiceManifest> = byId.values.sortedBy { it.serviceId.value }
}
