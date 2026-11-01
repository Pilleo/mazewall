package io.mazewall.profiler.internal.hotspot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.HexFormat

private const val MAX_MANIFEST_BYTES = 65536
private const val MAX_ASSET_FILES = 256
private const val MAX_ASSET_BYTES = 512L * 1024 * 1024
private const val ASSET_SCHEMA = 1
private const val CAPTURE_ABI = 2
private const val WIRE_SCHEMA = 2
private const val COLLECTOR_VERSION = "2"
private const val MAX_OCI_JSON_DEPTH = 16
private const val MAX_MANIFEST_JSON_DEPTH = 2
private const val OCI_SCHEMA = 2
private const val GADGET_REVISION = "7097db144ccd8dab15373f27ec98d2c3d22e4588"
private const val PROFILER_REVISION = "ef6cf29492c8a980f889052903fa2dd0c0765cc0"
private const val PATCH_SHA256 = "b9a9d70db7e37bba07293a635cac94cc540688b85e1f3aa31c2e9ad25cfeecf3"
private val SHA256_PATTERN = Regex("[a-f0-9]{64}")

/** Local integrity/version admission. The operator supplies a trusted manifest and owns installation. */
internal data class HotSpotAssets private constructor(
    val collector: Path,
    val store: Path,
    val image: String,
) {
    companion object {
        fun load(directory: Path): HotSpotAssets = load(directory, null)

        fun load(
            directory: Path,
            timings: HotSpotAssetTimings?,
        ): HotSpotAssets = load(directory, timings, JavaHotSpotAssetDigest)

        fun load(
            directory: Path,
            timings: HotSpotAssetTimings?,
            digest: HotSpotAssetDigest,
        ): HotSpotAssets {
            val root = directory.toAbsolutePath().normalize()
            val manifest = measuredAssetPhase(timings, "manifest") {
            val manifestPath = ownedFile(root, "manifest.json")
            val bytes = Files.newInputStream(manifestPath).use { it.readNBytes(MAX_MANIFEST_BYTES + 1) }
            require(bytes.size <= MAX_MANIFEST_BYTES) { "HotSpot asset manifest exceeds byte limit" }
            val text = bytes.toString(Charsets.UTF_8)
            checkAssetJsonDepth(text, MAX_MANIFEST_JSON_DEPTH)
            val manifest = Json.decodeFromString<HotSpotAssetManifest>(text)
            manifest.validate()
                manifest
            }
            measuredAssetPhase(timings, "checksums") {
            var totalBytes = 0L
            for ((name, expected) in manifest.files) {
                require(SHA256_PATTERN.matches(expected)) { "Invalid asset checksum: $name" }
                val path = ownedFile(root, name)
                val remaining = MAX_ASSET_BYTES - totalBytes
                require(Files.size(path) <= remaining) { "HotSpot asset size limit exceeded" }
                val actual = digest.digest(path, remaining)
                require(actual.bytesHashed <= remaining) { "HotSpot asset hash byte budget exceeded" }
                totalBytes += actual.bytesHashed
                require(HexFormat.of().formatHex(actual.bytes()) == expected) { "HotSpot asset checksum mismatch: $name" }
                if (name.startsWith("oci/blobs/sha256/")) {
                    require(name.removePrefix("oci/blobs/sha256/") == expected) { "OCI blob digest mismatch: $name" }
                }
            }
            }
            measuredAssetPhase(timings, "oci_graph") { verifyOciGraph(root, manifest) }
            return measuredAssetPhase(timings, "collector") {
            val collector = ownedFile(root, "mw-hotspot-collector")
            require(Files.isExecutable(collector)) { "HotSpot collector is not executable" }
                HotSpotAssets(collector, root.resolve("oci"), "ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@${manifest.gadgetDigest}")
            }
        }
    }
}

@Serializable
private data class HotSpotAssetManifest(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("collector_version") val collectorVersion: String,
    @SerialName("capture_abi") val captureAbi: Int,
    @SerialName("wire_schema") val wireSchema: Int,
    val platform: String,
    @SerialName("gadget_revision") val gadgetRevision: String,
    @SerialName("profiler_revision") val profilerRevision: String,
    @SerialName("patch_sha256") val patchSha256: String,
    @SerialName("gadget_digest") val gadgetDigest: String,
    val files: Map<String, String>,
) {
    fun validate() {
        require(schemaVersion == ASSET_SCHEMA && collectorVersion == COLLECTOR_VERSION) { "Unsupported HotSpot asset version" }
        require(captureAbi == CAPTURE_ABI && wireSchema == WIRE_SCHEMA) { "Unsupported HotSpot asset ABI/schema" }
        require(platform == "linux-amd64") { "Unsupported HotSpot asset platform" }
        require(gadgetRevision == GADGET_REVISION && profilerRevision == PROFILER_REVISION && patchSha256 == PATCH_SHA256) { "Unsupported HotSpot engine provenance" }
        require(gadgetDigest.startsWith("sha256:") && SHA256_PATTERN.matches(gadgetDigest.removePrefix("sha256:"))) { "Invalid gadget digest" }
        require(files.size in 1..MAX_ASSET_FILES) { "HotSpot asset file count exceeds limit" }
        val required = setOf("mw-hotspot-collector", "oci/oci-layout", "oci/index.json", "oci/blobs/sha256/${gadgetDigest.removePrefix("sha256:")}")
        require(files.keys.containsAll(required)) { "Incomplete HotSpot asset manifest" }
    }
}

private fun ownedFile(
    root: Path,
    name: String,
): Path {
    require(name.isNotEmpty() && name.length <= MAX_MANIFEST_BYTES && !name.startsWith('/')) { "Invalid asset path" }
    val components = name.split('/')
    require(components.all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it }) { "Invalid asset path: $name" }
    var path = root
    require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Asset directory is missing or symbolic" }
    for (component in components) {
        path = path.resolve(component)
        require(!Files.isSymbolicLink(path)) { "Symbolic asset path: $name" }
    }
    require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Missing HotSpot asset: $name" }
    return path
}

private fun checkAssetJsonDepth(
    text: String,
    maximum: Int,
) {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in text) {
        when {
            escaped -> escaped = false
            quoted && character == '\\' -> escaped = true
            character == '"' -> quoted = !quoted
            !quoted && (character == '{' || character == '[') -> {
                depth++
                require(depth <= maximum) { "HotSpot asset JSON nesting limit exceeded" }
            }
            !quoted && (character == '}' || character == ']') -> depth--
        }
    }
}

@Serializable
private data class OciDocument(
    val schemaVersion: Int,
    val manifests: List<OciDescriptor> = emptyList(),
    val layers: List<OciDescriptor> = emptyList(),
    val config: OciDescriptor? = null,
)

@Serializable
private data class OciDescriptor(
    val mediaType: String,
    val digest: String,
    val size: Long,
)

private val OCI_JSON = Json { ignoreUnknownKeys = true }

private fun readOciDocument(path: Path): OciDocument {
    val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_MANIFEST_BYTES + 1) }
    require(bytes.size <= MAX_MANIFEST_BYTES) { "OCI descriptor exceeds byte limit" }
    val text = bytes.toString(Charsets.UTF_8)
    checkAssetJsonDepth(text, MAX_OCI_JSON_DEPTH)
    val document = OCI_JSON.decodeFromString<OciDocument>(text)
    require(document.schemaVersion == OCI_SCHEMA) { "Unsupported OCI schema" }
    return document
}

private fun verifyOciGraph(
    root: Path,
    manifest: HotSpotAssetManifest,
) {
    val index = readOciDocument(ownedFile(root, "oci/index.json"))
    require(index.manifests.any { it.digest == manifest.gadgetDigest }) { "Gadget digest absent from OCI index" }
    require(
        index.manifests.filter { it.digest == manifest.gadgetDigest }.all {
            it.mediaType in setOf("application/vnd.oci.image.index.v1+json", "application/vnd.oci.image.manifest.v1+json")
        },
    ) { "Unsupported selected OCI root media type" }
    val pending = ArrayDeque(index.manifests)
    val visited = mutableSetOf<String>()
    val mediaByDigest = mutableMapOf<String, String>()
    while (pending.isNotEmpty()) {
        require(pending.size + visited.size <= MAX_ASSET_FILES) { "OCI graph exceeds descriptor limit" }
        val descriptor = pending.removeFirst()
        val hash = descriptor.digest.removePrefix("sha256:")
        require(descriptor.digest.startsWith("sha256:") && SHA256_PATTERN.matches(hash)) { "Invalid OCI descriptor digest" }
        val name = "oci/blobs/sha256/$hash"
        require(manifest.files[name] == hash) { "Unlisted required OCI blob: $name" }
        val path = ownedFile(root, name)
        require(descriptor.size >= 0 && Files.size(path) == descriptor.size) { "OCI descriptor size mismatch" }
        val previous = mediaByDigest.putIfAbsent(hash, descriptor.mediaType)
        require(previous == null || previous == descriptor.mediaType) { "Conflicting OCI media type: $hash" }
        if (visited.add(hash) && descriptor.mediaType in setOf("application/vnd.oci.image.index.v1+json", "application/vnd.oci.image.manifest.v1+json")) {
            val document = readOciDocument(path)
            pending.addAll(document.manifests)
            pending.addAll(document.layers)
            document.config?.let { pending.add(it) }
        }
    }
}
