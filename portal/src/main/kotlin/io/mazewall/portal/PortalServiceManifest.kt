package io.mazewall.portal

/**
 * Immutable generated description of one service relocated into a portal worker.
 *
 * The fields are names and identifiers rather than [Class] values so loading a
 * manifest cannot initialize or construct guest code in the broker JVM.
 */
public data class PortalServiceManifest(
    val serviceId: PortalServiceId,
    val contractName: String,
    val implementationName: String,
    val dispatcherName: String,
    val schemaId: String,
    val isolationGroup: String,
    val instanceMode: PortalInstanceMode,
    val policyProfile: String,
    val workerArtifact: String,
) {
    init {
        requireJavaBinaryName(contractName, "contractName")
        requireJavaBinaryName(implementationName, "implementationName")
        requireJavaBinaryName(dispatcherName, "dispatcherName")
        require(contractName != implementationName) {
            "portal service ${serviceId.value} implementationName must differ from contractName"
        }
        requireManifestValue(schemaId, "schemaId")
        requireManifestValue(isolationGroup, "isolationGroup")
        requireManifestValue(policyProfile, "policyProfile")
        requireManifestValue(workerArtifact, "workerArtifact")
    }

    private fun requireJavaBinaryName(
        value: String,
        field: String,
    ) {
        require(value.matches(JAVA_BINARY_NAME)) {
            "portal service ${serviceId.value} $field must be a Java binary name: $value"
        }
    }

    private fun requireManifestValue(
        value: String,
        field: String,
    ) {
        require(value.isNotBlank() && '\n' !in value && '\r' !in value) {
            "portal service ${serviceId.value} $field must be non-blank single-line text"
        }
    }

    private companion object {
        val JAVA_BINARY_NAME: Regex = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:[.$][A-Za-z_$][A-Za-z0-9_$]*)*")
    }
}

/** Stable explicit selector for an intentional implementation of a contract. */
@JvmInline
public value class PortalServiceId(
    public val value: String,
) {
    init {
        require(value.matches(SERVICE_ID)) { "portal service ID must be non-blank URL-safe text: $value" }
    }

    private companion object {
        val SERVICE_ID: Regex = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    }
}

/** Defines whether worker loss can be followed by automatic capacity replacement. */
public enum class PortalInstanceMode {
    STATEFUL_SINGLETON,
    STATELESS,
}

/** Strict UTF-8 resource format used by generated host and worker metadata. */
public object PortalServiceManifestCodec {
    private const val FORMAT_KEY: String = "format"
    private const val FORMAT_VALUE: String = "1"
    private const val MAX_RESOURCE_BYTES: Int = 4_096

    private val expectedKeys: Set<String> =
        setOf(
            FORMAT_KEY,
            "serviceId",
            "contractName",
            "implementationName",
            "dispatcherName",
            "schemaId",
            "isolationGroup",
            "instanceMode",
            "policyProfile",
            "workerArtifact",
        )

    public fun encode(manifest: PortalServiceManifest): String =
        buildString {
            appendLine("$FORMAT_KEY=$FORMAT_VALUE")
            appendLine("serviceId=${manifest.serviceId.value}")
            appendLine("contractName=${manifest.contractName}")
            appendLine("implementationName=${manifest.implementationName}")
            appendLine("dispatcherName=${manifest.dispatcherName}")
            appendLine("schemaId=${manifest.schemaId}")
            appendLine("isolationGroup=${manifest.isolationGroup}")
            appendLine("instanceMode=${manifest.instanceMode.name}")
            appendLine("policyProfile=${manifest.policyProfile}")
            appendLine("workerArtifact=${manifest.workerArtifact}")
        }

    public fun decode(resource: String): PortalServiceManifest {
        require(resource.toByteArray(Charsets.UTF_8).size <= MAX_RESOURCE_BYTES) {
            "portal service manifest exceeds $MAX_RESOURCE_BYTES bytes"
        }
        require('\r' !in resource) { "portal service manifest must use LF line endings" }
        val values = linkedMapOf<String, String>()
        resource.removeSuffix("\n").lineSequence().forEach { line ->
            require(line.isNotEmpty()) { "portal service manifest contains an empty line" }
            val delimiter = line.indexOf('=')
            require(delimiter > 0) { "portal service manifest entry must be key=value: $line" }
            val key = line.substring(0, delimiter)
            val value = line.substring(delimiter + 1)
            require(key in expectedKeys) { "portal service manifest has unknown key: $key" }
            require(values.put(key, value) == null) { "portal service manifest has duplicate key: $key" }
        }
        require(values.keys == expectedKeys) {
            "portal service manifest keys must be exactly ${expectedKeys.sorted().joinToString()}"
        }
        require(values.getValue(FORMAT_KEY) == FORMAT_VALUE) {
            "unsupported portal service manifest format: ${values.getValue(FORMAT_KEY)}"
        }
        return PortalServiceManifest(
            serviceId = PortalServiceId(values.getValue("serviceId")),
            contractName = values.getValue("contractName"),
            implementationName = values.getValue("implementationName"),
            dispatcherName = values.getValue("dispatcherName"),
            schemaId = values.getValue("schemaId"),
            isolationGroup = values.getValue("isolationGroup"),
            instanceMode = PortalInstanceMode.valueOf(values.getValue("instanceMode")),
            policyProfile = values.getValue("policyProfile"),
            workerArtifact = values.getValue("workerArtifact"),
        )
    }
}

/** Loads generated service manifests through an explicit, bounded catalog resource. */
public object PortalServiceManifestCatalog {
    private const val RESOURCE_ROOT: String = "META-INF/mazewall/portal"
    private const val INDEX_RESOURCE: String = "$RESOURCE_ROOT/services.index"
    private const val SERVICE_RESOURCE_ROOT: String = "$RESOURCE_ROOT/services"
    private const val MAX_INDEX_BYTES: Int = 4_096

    public fun load(loader: ClassLoader): List<PortalServiceManifest> {
        val serviceIds = linkedSetOf<PortalServiceId>()
        loader
            .getResources(INDEX_RESOURCE)
            .toList()
            .sortedBy { it.toExternalForm() }
            .forEach { resource ->
                readBounded(resource, MAX_INDEX_BYTES, "portal service manifest index")
                    .removeSuffix("\n")
                    .lineSequence()
                    .forEach { line ->
                        require(line.isNotBlank()) { "portal service manifest index contains a blank service ID" }
                        val serviceId = PortalServiceId(line)
                        require(serviceIds.add(serviceId)) { "portal service manifest index has duplicate service ID: ${serviceId.value}" }
                    }
            }
        return serviceIds
            .sortedBy { it.value }
            .map { serviceId ->
                val resourceName = "$SERVICE_RESOURCE_ROOT/${serviceId.value}"
                val resources = loader.getResources(resourceName).toList()
                require(resources.size == 1) {
                    "portal service manifest $resourceName must resolve exactly once, found ${resources.size}"
                }
                val manifest = PortalServiceManifestCodec.decode(readBounded(resources.single(), 4_096, "portal service manifest"))
                require(manifest.serviceId == serviceId) {
                    "portal service manifest $resourceName declares ${manifest.serviceId.value}"
                }
                manifest
            }
    }

    private fun readBounded(
        resource: java.net.URL,
        limit: Int,
        label: String,
    ): String =
        resource.openStream().use { stream ->
            val bytes = stream.readNBytes(limit + 1)
            require(bytes.size <= limit) { "$label exceeds $limit bytes: $resource" }
            bytes.toString(Charsets.UTF_8)
        }
}
