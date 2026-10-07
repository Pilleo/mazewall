package io.mazewall.portal

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/** Verifies and extracts a worker artifact into a digest-addressed cache entry. */
public object WorkerBundleMaterializer {
    private const val maximumEntryCount: Int = 256
    private const val maximumUncompressedBytes: Long = 64L * 1024L * 1024L
    private const val completionMarker: String = ".mazewall-worker-bundle"

    @JvmStatic
    public fun materialize(
        artifact: PortalWorkerArtifact,
        cacheDirectory: Path,
    ): MaterializedWorkerBundle = materialize(artifact, cacheDirectory, artifact.sha256)

    /**
     * Materializes under a caller-derived identity. Runtime callers include the
     * immutable bundle digest and the manifest schema in this key.
     */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught") // Staging must be deleted after every extraction failure.
    public fun materialize(
        artifact: PortalWorkerArtifact,
        cacheDirectory: Path,
        cacheKey: String,
    ): MaterializedWorkerBundle {
        require(cacheKey.isNotBlank()) { "worker bundle cache key must not be blank" }
        val source = verifiedArtifact(artifact)
        val cache = verifiedCacheDirectory(cacheDirectory)
        val destination = cache.resolve(cacheDirectoryName(artifact, cacheKey)).normalize()
        if (Files.exists(destination)) return materializedBundle(destination, artifact.sha256)
        val staging = Files.createTempDirectory(cache, ".worker-")
        try {
            extractBundle(source, staging)
            Files.writeString(staging.resolve(completionMarker), artifact.sha256.lowercase())
            publish(staging, destination, artifact.sha256)
            return materializedBundle(destination, artifact.sha256)
        } catch (failure: Throwable) {
            staging.toFile().deleteRecursively()
            throw failure
        }
    }

    private fun verifiedArtifact(artifact: PortalWorkerArtifact): Path {
        val source = artifact.path.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw PortalWorkerArtifactUnavailableException(PortalServiceId("worker"), source.toString())
        }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(source).use { stream ->
            val buffer = ByteArray(8_192)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val encoded = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
        if (!encoded.equals(artifact.sha256, ignoreCase = true)) {
            throw PortalWorkerArtifactUnavailableException(PortalServiceId("worker"), source.toString())
        }
        return source
    }

    private fun verifiedCacheDirectory(cacheDirectory: Path): Path = VerifiedWorkerCache.directory(cacheDirectory)

    private fun cacheDirectoryName(
        artifact: PortalWorkerArtifact,
        cacheKey: String,
    ): String = if (cacheKey.equals(artifact.sha256, ignoreCase = true)) artifact.sha256.lowercase() else sha256(cacheKey.toByteArray())

    private fun publish(
        staging: Path,
        destination: Path,
        digest: String,
    ) {
        try {
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, destination)
            }
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            verifiedPublishedBundle(destination, digest)
            staging.toFile().deleteRecursively()
        }
    }

    private fun extractBundle(
        source: Path,
        staging: Path,
    ) {
        ZipFile(source.toFile()).use { zip ->
            var entryCount = 0
            var extractedBytes = 0L
            zip.entries().asSequence().forEach { entry ->
                require(++entryCount <= maximumEntryCount) { "worker bundle contains more than $maximumEntryCount entries" }
                val output = safeOutput(staging, entry)
                extractedBytes += copyEntry(zip, entry, output)
                require(extractedBytes <= maximumUncompressedBytes) {
                    "worker bundle expands beyond $maximumUncompressedBytes bytes"
                }
            }
        }
    }

    private fun safeOutput(
        staging: Path,
        entry: java.util.zip.ZipEntry,
    ): Path {
        require(!entry.isDirectory) { "worker bundle must not contain directory entries: ${entry.name}" }
        val entryPath = Path.of(entry.name)
        require(!entryPath.isAbsolute && '\\' !in entry.name && entryPath.none { it.toString() == ".." }) {
            "unsafe worker bundle entry: ${entry.name}"
        }
        val output = staging.resolve(entry.name).normalize()
        require(output.startsWith(staging)) { "unsafe worker bundle entry: ${entry.name}" }
        Files.createDirectories(output.parent)
        return output
    }

    private fun copyEntry(
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        output: Path,
    ): Long {
        var written = 0L
        zip.getInputStream(entry).use { input ->
            Files.newOutputStream(output).use { outputStream ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    written += read
                    require(written <= maximumUncompressedBytes) {
                        "worker bundle expands beyond $maximumUncompressedBytes bytes"
                    }
                    outputStream.write(buffer, 0, read)
                }
            }
        }
        return written
    }

    /** Returns the only classpath a materialized worker bundle may contribute. */
    @JvmStatic
    public fun classpath(bundle: Path): String {
        return classpathEntries(bundle).joinToString(java.io.File.pathSeparator) { it.toString() }
    }

    private fun materializedBundle(
        destination: Path,
        digest: String,
    ): MaterializedWorkerBundle {
        val directory = verifiedPublishedBundle(destination, digest)
        return MaterializedWorkerBundle(directory, classpathEntries(directory))
    }

    private fun classpathEntries(bundle: Path): List<Path> {
        val libraryDirectory = bundle.resolve("lib")
        require(Files.isDirectory(libraryDirectory)) { "worker bundle does not contain a lib directory: $bundle" }
        val libraries =
            Files.list(libraryDirectory).use { entries ->
                entries
                    .filter { !Files.isSymbolicLink(it) && Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .sorted()
                    .toList()
            }
        require(libraries.isNotEmpty()) { "worker bundle does not contain any worker jars: $bundle" }
        return libraries
    }

    private fun verifiedPublishedBundle(
        destination: Path,
        digest: String,
    ): Path {
        require(!Files.isSymbolicLink(destination) && Files.isDirectory(destination)) {
            "worker bundle cache target is not a directory: $destination"
        }
        val marker = destination.resolve(completionMarker)
        require(!Files.isSymbolicLink(marker) && Files.isRegularFile(marker)) {
            "worker bundle cache target is unpublished: $destination"
        }
        require(Files.readString(marker) == digest.lowercase()) {
            "worker bundle cache target has an unexpected digest: $destination"
        }
        return destination
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
}
