package io.mazewall.portal

import java.nio.file.Path

/**
 * Configuration for [PortalRuntime]. The host names a worker bundle by manifest key,
 * then resolves its immutable, digest-verified artifact from generated metadata
 * (or an explicit application-provided override).
 */
public class PortalRuntimeConfig(
    manifests: List<PortalServiceManifest>,
    workerArtifacts: Map<String, PortalWorkerArtifact> = emptyMap(),
    public val workerBundleCache: Path = defaultCacheDirectory(),
    workerProfiles: Map<String, PortalWorkerProfile> = emptyMap(),
    public val cgroupRoot: Path? = null,
    public val grantRoots: PortalGrantRoots = PortalGrantRoots.empty(),
    public val limits: PortalRuntimeLimits = PortalRuntimeLimits(),
    launchProfiles: Map<String, PortalLaunchProfile>,
    requiredLaunchProfiles: Set<String> = emptySet(),
) {
    /** Retains the original constructor and Kotlin default-argument bridge. */
    public constructor(
        manifests: List<PortalServiceManifest>,
        workerArtifacts: Map<String, PortalWorkerArtifact> = emptyMap(),
        workerBundleCache: Path = defaultCacheDirectory(),
        workerProfiles: Map<String, PortalWorkerProfile> = emptyMap(),
        cgroupRoot: Path? = null,
        grantRoots: PortalGrantRoots = PortalGrantRoots.empty(),
        limits: PortalRuntimeLimits = PortalRuntimeLimits(),
    ) : this(manifests, workerArtifacts, workerBundleCache, workerProfiles, cgroupRoot, grantRoots, limits, emptyMap())

    public val manifests: List<PortalServiceManifest> = java.util.List.copyOf(manifests)
    public val workerArtifacts: Map<String, PortalWorkerArtifact> = java.util.Map.copyOf(workerArtifacts)
    public val workerProfiles: Map<String, PortalWorkerProfile> = java.util.Map.copyOf(workerProfiles)
    public val launchProfiles: Map<String, PortalLaunchProfile> = java.util.Map.copyOf(launchProfiles)
    public val requiredLaunchProfiles: Set<String> = java.util.Set.copyOf(requiredLaunchProfiles)

    /** An unnamed profile retains inherited deployment behavior and the required resource contract. */
    internal fun launchProfile(manifest: PortalServiceManifest): PortalLaunchProfile =
        launchProfiles[manifest.policyProfile]
        ?: if (manifest.policyProfile in requiredLaunchProfiles) throw PortalLaunchProfileUnavailableException(manifest.policyProfile) else PortalLaunchProfile()

    internal fun profile(manifest: PortalServiceManifest): PortalWorkerProfile = workerProfiles[manifest.policyProfile] ?: throw PortalWorkerProfileUnavailableException(manifest.policyProfile)

    internal fun validateProfiles() {
        manifests.forEach { manifest ->
            val profile = profile(manifest)
            launchProfile(manifest).contract()
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
            grantRoots: PortalGrantRoots = PortalGrantRoots.empty(),
            limits: PortalRuntimeLimits = PortalRuntimeLimits(),
            launchProfiles: Map<String, PortalLaunchProfile>,
            requiredLaunchProfiles: Set<String> = emptySet(),
        ): PortalRuntimeConfig {
            val generated = PortalWorkerArtifactCatalog.load(loader, workerBundleCache)
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
                grantRoots = grantRoots,
                limits = limits,
                launchProfiles = launchProfiles,
                requiredLaunchProfiles = requiredLaunchProfiles,
            )
        }

        /** Retains the original load entry and Kotlin default-argument bridge. */
        @JvmStatic
        public fun load(
            loader: ClassLoader = defaultLoader(),
            workerArtifacts: Map<String, PortalWorkerArtifact> = emptyMap(),
            workerBundleCache: Path = defaultCacheDirectory(),
            workerProfiles: Map<String, PortalWorkerProfile> = emptyMap(),
            cgroupRoot: Path? = null,
            grantRoots: PortalGrantRoots = PortalGrantRoots.empty(),
            limits: PortalRuntimeLimits = PortalRuntimeLimits(),
        ): PortalRuntimeConfig = load(loader, workerArtifacts, workerBundleCache, workerProfiles, cgroupRoot, grantRoots, limits, emptyMap())

        private fun defaultLoader(): ClassLoader = Thread.currentThread().contextClassLoader ?: PortalRuntimeConfig::class.java.classLoader

        private fun defaultCacheDirectory(): Path = Path.of(System.getProperty("java.io.tmpdir"), "mazewall", "portal-workers")
    }
}
