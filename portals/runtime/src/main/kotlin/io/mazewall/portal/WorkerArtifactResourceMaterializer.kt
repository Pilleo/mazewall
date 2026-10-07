package io.mazewall.portal

import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Copies opaque resource bytes transactionally; archive extraction remains a separate owner. */
internal object WorkerArtifactResourceMaterializer {
    private const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
    private const val COPY_BUFFER_BYTES = 8_192

    @Suppress("TooGenericExceptionCaught") // Own staging rollback on every failure and preserve fatal-first cleanup evidence.
    fun materialize(
        resource: URL,
        sha256: String,
        cacheDirectory: Path,
    ): PortalWorkerArtifact {
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "invalid worker resource digest" }
        val digest = sha256.lowercase()
        val cache = VerifiedWorkerCache.directory(cacheDirectory)
        val destination = cache.resolve(".worker-artifact-$digest.zip")
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(destination) && Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) { "worker resource cache destination is not a regular file" }
            require(hash(destination) == digest) { "cached worker resource digest mismatch" }
            return PortalWorkerArtifact(destination, digest)
        }
        val staging = Files.createTempFile(cache, ".worker-artifact-", ".tmp")
        try {
            require(copyResource(resource, staging) == digest) { "worker resource digest mismatch" }
            require(!Files.isSymbolicLink(destination)) { "worker resource cache destination is a symbolic link" }
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
            return PortalWorkerArtifact(destination, digest)
        } catch (failure: Throwable) {
            val cleanup = runCatching { Files.deleteIfExists(staging) }.exceptionOrNull()
            throw (
                cleanup?.let {
                io.mazewall.platform.fd.CleanupFailures
                .merge(failure, it)
            } ?: failure
            )
        }
    }

    private fun copyResource(
        resource: URL,
        staging: Path,
    ): String {
        val computed = MessageDigest.getInstance("SHA-256")
        resource.openStream().use { input ->
            Files.newOutputStream(staging).use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= MAX_ARCHIVE_BYTES) { "worker resource archive exceeds $MAX_ARCHIVE_BYTES bytes" }
                    computed.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        return hex(computed.digest())
    }

    private fun hash(path: Path): String {
        require(Files.size(path) <= MAX_ARCHIVE_BYTES) { "cached worker archive exceeds $MAX_ARCHIVE_BYTES bytes" }
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var copied = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                require(copied <= MAX_ARCHIVE_BYTES) { "cached worker archive exceeds $MAX_ARCHIVE_BYTES bytes" }
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
}
