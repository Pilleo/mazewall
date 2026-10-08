package io.mazewall.portal

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile

internal class PortalWorkerLaunch(
    manifests: List<PortalServiceManifest>,
    val artifact: PortalWorkerArtifact,
) {
    val manifests: List<PortalServiceManifest> = java.util.List.copyOf(manifests)
}

/** Identity of actual launch inputs, captured independently on both sides before guest entry. */
internal data class PortalWorkerGeneration(
    val inputs: String,
    val manifests: String,
    val runtime: String,
) {
    companion object {
        private const val RESOURCE_ROOT = "META-INF/mazewall/portal"
        private const val MAX_METADATA_BYTES = 4096

        fun capture(
            classpath: String,
            bundle: Path?,
            manifests: List<PortalServiceManifest>,
            expectedArtifactDigest: String? = null,
        ): PortalWorkerGeneration =
            PortalWorkerGeneration(
                digest(
                    buildString {
                    val artifactDigest = bundle?.let(::fileDigest)
                    require(expectedArtifactDigest == null || artifactDigest == expectedArtifactDigest.lowercase(java.util.Locale.ROOT)) { "worker artifact changed after verified materialization" }
                    appendLine(artifactDigest ?: "direct-classpath")
                    entries(classpath).forEach { path ->
                        appendLine(path.toAbsolutePath().normalize())
                        if (Files.isDirectory(path)) {
                            Files.walk(path).use { paths ->
                                paths.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                                    appendLine(path.relativize(file))
                                    appendLine(fileDigest(file))
                                }
                            }
                        } else {
                            appendLine(fileDigest(path))
                        }
                    }
                },
                ),
                digest(manifests.sortedBy { it.serviceId.value }.joinToString("") { PortalServiceManifestCodec.encode(it) }),
                digest(listOf("java.runtime.version", "java.vendor", "java.vm.name", "os.arch", "java.home").joinToString("\n") { "$it=${System.getProperty(it)}" }),
            )

        /** Reads metadata as data only: no worker classes or guest initializers are loaded in the host. */
        fun readManifests(classpath: String): List<PortalServiceManifest> {
            val indexes = resources(classpath, "$RESOURCE_ROOT/services.index")
            val ids = indexes.flatMap { it.removeSuffix("\n").split('\n') }.map(::PortalServiceId)
            require(ids.toSet().size == ids.size) { "duplicate worker manifest index service" }
            return ids.sortedBy { it.value }.map { id ->
                val records = resources(classpath, "$RESOURCE_ROOT/services/${id.value}")
                require(records.size == 1) { "worker manifest must resolve exactly once: ${id.value}" }
                PortalServiceManifestCodec.decode(records.single()).also {
                    require(it.serviceId == id) { "worker manifest index identity mismatch" }
                }
            }
        }

        private fun resources(
            classpath: String,
            name: String,
        ): List<String> =
            entries(classpath).flatMap { path ->
            if (Files.isDirectory(path)) {
                val resource = path.resolve(name)
                if (Files.exists(resource)) listOf(Files.newInputStream(resource).use(::readMetadata)) else emptyList()
            } else {
                JarFile(path.toFile()).use { jar ->
                    jar
                        .entries()
                        .asSequence()
                        .filter { it.name == name }
                        .map { entry ->
                        jar.getInputStream(entry).use(::readMetadata)
                    }.toList()
                }
            }
        }

        private fun readMetadata(stream: java.io.InputStream): String {
            val bytes = stream.readNBytes(MAX_METADATA_BYTES + 1)
            require(bytes.size <= MAX_METADATA_BYTES) { "oversized worker generation metadata" }
            return bytes.toString(Charsets.UTF_8)
        }

        private fun entries(classpath: String): List<Path> {
            require(classpath.isNotBlank()) { "worker generation requires a classpath" }
            return classpath.split(java.io.File.pathSeparator).map { entry ->
                require(entry.isNotBlank() && '*' !in entry && '\n' !in entry && '\r' !in entry) { "worker generation requires explicit canonical classpath entries" }
                Path.of(entry)
            }
        }

        private fun fileDigest(path: Path): String {
            val hash = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { stream ->
                val buffer = ByteArray(8192)
                var count = stream.read(buffer)
                while (count >= 0) {
                    hash.update(buffer, 0, count)
                    count = stream.read(buffer)
                }
            }
            return hash.digest().toHex()
        }

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

        private fun ByteArray.toHex(): String =
            java.util.HexFormat
            .of()
            .formatHex(this)
    }
}
