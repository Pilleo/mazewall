package io.mazewall.portal

import java.nio.file.Path

/**
 * Configuration for [PortalRuntime]. The host names a worker bundle by manifest key,
 * then resolves its immutable, digest-verified artifact from generated metadata
 * (or an explicit application-provided override).
 */
public class PortalRuntimeConfig(
    public val manifests: List<PortalServiceManifest>,
    public val workerArtifacts: Map<String, PortalWorkerArtifact> = emptyMap(),
    public val workerBundleCache: Path = defaultCacheDirectory(),
    public val workerProfiles: Map<String, PortalWorkerProfile> = emptyMap(),
    public val cgroupRoot: Path? = null,
) {
    internal fun profile(manifest: PortalServiceManifest): PortalWorkerProfile = workerProfiles[manifest.policyProfile] ?: throw PortalWorkerProfileUnavailableException(manifest.policyProfile)

    internal fun validateProfiles() {
        manifests.forEach { manifest ->
            val profile = profile(manifest)
            if (profile.requiresCgroup && cgroupRoot == null) {
                throw PortalCgroupUnavailableException("profile ${manifest.policyProfile} requires a delegated cgroup root")
            }
        }
    }

    internal fun workerClasspath(manifest: PortalServiceManifest): String {
        val artifact =
            workerArtifacts[manifest.workerArtifact]
                ?: throw PortalWorkerArtifactUnavailableException(manifest.serviceId, manifest.workerArtifact)
        val bundle =
            WorkerBundleMaterializer.materialize(
                artifact,
                workerBundleCache,
                "${artifact.sha256.lowercase()}\n${manifest.schemaId}",
            )
        return bundle.classpath.joinToString(java.io.File.pathSeparator) { it.toString() }
    }

    public companion object {
        /** Loads every generated manifest visible to the current application class loader. */
        @JvmStatic
        public fun load(
            loader: ClassLoader = defaultLoader(),
            workerArtifacts: Map<String, PortalWorkerArtifact> = emptyMap(),
            workerBundleCache: Path = defaultCacheDirectory(),
            workerProfiles: Map<String, PortalWorkerProfile> = emptyMap(),
            cgroupRoot: Path? = null,
        ): PortalRuntimeConfig {
            val generated = PortalWorkerArtifactCatalog.load(loader)
            generated.forEach { (key, generatedArtifact) ->
                workerArtifacts[key]?.let { explicitArtifact ->
                    require(explicitArtifact == generatedArtifact) {
                        "explicit portal worker artifact for $key differs from generated metadata"
                    }
                }
            }
            return PortalRuntimeConfig(
                manifests = PortalServiceManifestCatalog.load(loader),
                workerArtifacts = generated + workerArtifacts,
                workerBundleCache = workerBundleCache,
                workerProfiles = workerProfiles,
                cgroupRoot = cgroupRoot,
            )
        }

        private fun defaultLoader(): ClassLoader = Thread.currentThread().contextClassLoader ?: PortalRuntimeConfig::class.java.classLoader

        private fun defaultCacheDirectory(): Path = Path.of(System.getProperty("java.io.tmpdir"), "mazewall", "portal-workers")
    }
}
