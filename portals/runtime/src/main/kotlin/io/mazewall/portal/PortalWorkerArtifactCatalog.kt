package io.mazewall.portal

import java.net.URL
import java.nio.file.Path

/**
 * Loads generated references to opaque, digest-verified classpath worker resources.
 *
 * Portable metadata contains a resource name and digest, never a build-machine
 * path. [WorkerBundleMaterializer] remains the archive-to-child-classpath owner.
 */
public object PortalWorkerArtifactCatalog {
    private const val RESOURCE_ROOT: String = "META-INF/mazewall/portal/worker-artifacts"
    private const val INDEX_RESOURCE: String = "$RESOURCE_ROOT.index"
    private const val MAX_RESOURCE_BYTES: Int = 4_096
    private val expectedKeys: Set<String> = setOf("format", "resource", "sha256")

    /** Loads every explicitly indexed artifact visible to [loader]. */
    public fun load(
        loader: ClassLoader,
        cacheDirectory: Path = Path.of(System.getProperty("java.io.tmpdir"), "mazewall", "portal-workers"),
    ): Map<String, PortalWorkerArtifact> {
        val keys = linkedSetOf<String>()
        loader.getResources(INDEX_RESOURCE).toList().sortedBy { it.toExternalForm() }.forEach { resource ->
            readBounded(resource, "portal worker artifact index")
                .removeSuffix("\n")
                .lineSequence()
                .forEach { key ->
                    require(key.isNotBlank() && '\r' !in key && '/' !in key && '\\' !in key && ".." !in key) {
                        "portal worker artifact index contains an unsafe artifact key: $key"
                    }
                    require(keys.add(key)) { "portal worker artifact index has duplicate artifact key: $key" }
                }
        }
        return keys.sorted().associateWith { key ->
            val resourceName = "$RESOURCE_ROOT/$key"
            val resources = loader.getResources(resourceName).toList()
            require(resources.size == 1) {
                "portal worker artifact $resourceName must resolve exactly once, found ${resources.size}"
            }
            val (bundleResource, digest) = decode(readBounded(resources.single(), "portal worker artifact"))
            val bundles = loader.getResources(bundleResource).toList()
            require(bundles.size == 1) { "portal worker bundle $bundleResource must resolve exactly once, found ${bundles.size}" }
            WorkerArtifactResourceMaterializer.materialize(bundles.single(), digest, cacheDirectory)
        }
    }

    /** Encodes a stable resource record emitted by the code-generation plugin. */
    public fun encodeResource(
        resourceName: String,
        sha256: String,
    ): String {
        validateResource(resourceName, sha256)
        return "format=2\nresource=$resourceName\nsha256=${sha256.lowercase()}\n"
    }

    /** Decodes one bounded resource record. */
    private fun decode(resource: String): Pair<String, String> {
        require(resource.toByteArray(Charsets.UTF_8).size <= MAX_RESOURCE_BYTES) {
            "portal worker artifact exceeds $MAX_RESOURCE_BYTES bytes"
        }
        require('\r' !in resource) { "portal worker artifact must use LF line endings" }
        val values = linkedMapOf<String, String>()
        resource.removeSuffix("\n").lineSequence().forEach { line ->
            require(line.isNotEmpty()) { "portal worker artifact contains an empty line" }
            val delimiter = line.indexOf('=')
            require(delimiter > 0) { "portal worker artifact entry must be key=value: $line" }
            val key = line.substring(0, delimiter)
            require(key in expectedKeys) { "portal worker artifact has unknown key: $key" }
            require(values.put(key, line.substring(delimiter + 1)) == null) { "portal worker artifact has duplicate key: $key" }
        }
        require(values.keys == expectedKeys) {
            "portal worker artifact keys must be exactly ${expectedKeys.sorted().joinToString()}"
        }
        require(values.getValue("format") == "2") { "unsupported portal worker artifact format: ${values.getValue("format")}" }
        val name = values.getValue("resource")
        val digest = values.getValue("sha256")
        validateResource(name, digest)
        return name to digest.lowercase()
    }

    private fun validateResource(
        resourceName: String,
        sha256: String,
    ) {
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "invalid worker resource digest" }
        require(resourceName == "META-INF/mazewall/portal/worker-bundles/${sha256.lowercase()}.zip") { "worker bundle resource must be digest-addressed beneath the bundle root" }
    }

    private fun readBounded(
        resource: URL,
        label: String,
    ): String =
        resource.openStream().use { stream ->
            val bytes = stream.readNBytes(MAX_RESOURCE_BYTES + 1)
            require(bytes.size <= MAX_RESOURCE_BYTES) { "$label exceeds $MAX_RESOURCE_BYTES bytes: $resource" }
            bytes.toString(Charsets.UTF_8)
        }
}
