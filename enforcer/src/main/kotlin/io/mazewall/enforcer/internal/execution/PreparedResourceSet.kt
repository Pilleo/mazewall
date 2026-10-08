package io.mazewall.enforcer.internal.execution

import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.ffi.internal.PreparedPath
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.native.NativeEngine
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Exact object acquisitions. Read allowances are distinct from explicit read-only recipes. */
internal class PreparedResourceSet private constructor(
    val grants: List<Grant>,
    private val strict: List<PreparedPath>,
    private val digests: Map<PreparedPath, String>,
) : AutoCloseable {
    class Grant(
        val path: PreparedPath,
        val read: Boolean,
        val write: Boolean,
        val execute: Boolean,
    )

    val generation: String = sha256(
        (
            grants.map { "${it.path.identity}\u0000${it.read}\u0000${it.write}\u0000${it.execute}" } +
        strict.map { "strict\u0000${it.identity}\u0000${digests[it]}" }
        ).sorted().joinToString("\n").toByteArray(),
    )

    @Synchronized
    fun validate() {
        grants.forEach { it.path.validate() }
        strict.forEach { path ->
            path.validate()
            if (grants.any { it.write }) requireNoWritableAliases(path)
            digests[path]?.let { check(contentDigest(path) == it) { "Read-only resource content changed: ${path.requested}" } }
        }
    }

    fun allowsRead(path: Path): Boolean = grants.any { it.read && (it.path.resolved == path || it.path.directory && path.startsWith(it.path.resolved)) }

    @Suppress("TooGenericExceptionCaught") // every acquisition has one close attempt; retain all cleanup failures
    @Synchronized
    override fun close() {
        var failure: Throwable? = null
        (grants.map { it.path } + strict).asReversed().forEach {
            try {
                it.close()
            } catch (problem: Throwable) {
                failure = CleanupFailures.merge(failure, problem)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        private const val MAX_RESOURCE_BYTES = 256L * 1024 * 1024

        @Suppress("TooGenericExceptionCaught") // unwind partial acquisition without erasing original failure
        fun capture(
            policy: PolicyDefinition<*>,
            native: NativeEngine,
            readOnlyPaths: Set<Path> = emptySet(),
        ): PreparedResourceSet {
            require(policy.enforceLandlock) { "Prepared resource contracts require filesystem containment" }
            val owned = mutableListOf<PreparedPath>()
            try {
                val reads = policy.allowedFsReadPaths.map { Path.of(it.value).toAbsolutePath().normalize() }.toSet()
                val writes = policy.allowedFsWritePaths.map { Path.of(it.value).toAbsolutePath().normalize() }.toSet()
                val explicit = (reads + writes).map { path ->
                    val pinned = PreparedPath.open(path, native).also { owned.add(it) }
                    Grant(pinned, path in reads, path in writes, false)
                }
                val strict = readOnlyPaths.map { path -> PreparedPath.open(path, native).also { owned.add(it) } }
                strict.forEach { path ->
                    require(explicit.any { it.read && contains(it.path, path) }) { "Read-only recipe lacks an explicit read allowance: ${path.requested}" }
                    require(explicit.none { it.write && (contains(it.path, path) || contains(path, it.path)) }) {
                        "Writable resource contradicts read-only recipe: ${path.requested}"
                    }
                    if (writes.isNotEmpty()) requireNoWritableAliases(path)
                }
                val runtime = linkedSetOf(Path.of(checkNotNull(System.getProperty("java.home"))))
                System
                    .getProperty("java.class.path", "")
                    .split(File.pathSeparator)
                    .filter { it.isNotEmpty() }
                    .forEach { runtime.add(Path.of(it)) }
                val automatic = runtime.map { path -> Grant(PreparedPath.open(path, native).also { owned.add(it) }, true, false, true) }
                val digests = strict.filter { !it.directory }.associateWith { contentDigest(it) }
                return PreparedResourceSet(explicit + automatic, strict, digests).also { it.validate() }
            } catch (failure: Throwable) {
                var primary = failure
                owned.asReversed().forEach { path ->
                    try {
                    path.close()
                } catch (cleanup: Throwable) {
                    primary = CleanupFailures.merge(primary, cleanup)
                }
                }
                throw primary
            }
        }

        private fun contains(
            parent: PreparedPath,
            child: PreparedPath,
        ): Boolean =
            parent.identity == child.identity ||
                parent.resolved == child.resolved ||
                java.nio.file.Files
                    .isSameFile(parent.descriptorPath(), child.descriptorPath()) ||
                parent.directory &&
                child.resolved.startsWith(parent.resolved)

        private fun requireNoWritableAliases(path: PreparedPath) {
            require(!path.directory) { "Strict read-only directory with writable resources cannot exclude descendant aliases; select exact read-only files" }
            val links = java.nio.file.Files
                .getAttribute(path.descriptorPath(), "unix:nlink") as Number
            require(links.toLong() == 1L) { "Multiply-linked read-only file may be reachable through writable resources: ${path.requested}" }
        }

        private fun contentDigest(path: PreparedPath): String =
            java.nio.file.Files.newInputStream(path.descriptorPath()).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                var count = input.read(buffer)
                var bytes = 0L
                while (count >= 0) {
                    bytes += count
                    require(bytes <= MAX_RESOURCE_BYTES) { "Read-only resource exceeds bounded identity budget" }
                    digest.update(buffer, 0, count)
                    count = input.read(buffer)
                }
                HexFormat.of().formatHex(digest.digest())
            }

        private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
