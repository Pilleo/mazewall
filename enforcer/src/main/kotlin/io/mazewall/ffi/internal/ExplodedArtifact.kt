package io.mazewall.ffi.internal

import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.native.NativeEngine
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Bounded complete deployment-tree snapshot, not immutable directory descendants. */
internal class ExplodedArtifact private constructor(
    private val root: PreparedPath,
    private val entries: Map<String, PreparedPath>,
    val sha256: String,
) : AutoCloseable {
    val generation: String = ArtifactBytes.hash(
        entries
        .map { "${it.key}\u0000${it.value.identity}" }
        .sorted()
        .joinToString("\n")
        .toByteArray(),
    )

    fun validate() {
        root.validate()
        check(list(root.resolved).keys == entries.keys) { "Exploded deployment entries changed" }
        entries.values.forEach { it.validate() }
        check(treeDigest(entries) == sha256) { "Exploded deployment bytes changed" }
    }

    fun selectedBytes(name: String): ByteArray {
        val selected = checkNotNull(entries[name]) { "Selected class is absent from exploded deployment" }
        require(!selected.directory) { "Selected class is a directory" }
        return Files.newInputStream(selected.descriptorPath()).use { ArtifactBytes.read(it) }
    }

    @Suppress("TooGenericExceptionCaught") // attempt every genuine leaf/directory release, preserving fatal-first failure
    override fun close() {
        var failure: Throwable? = null
        entries.values.toList().asReversed().forEach {
            try {
                it.close()
            } catch (problem: Throwable) {
                failure = CleanupFailures.merge(failure, problem)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        private const val MAX_DEPTH = 64

        @Suppress("TooGenericExceptionCaught") // partially captured descendants remain owned through rollback
        fun capture(
            root: PreparedPath,
            native: NativeEngine,
        ): ExplodedArtifact {
            val owned = linkedMapOf<String, PreparedPath>()
            try {
                list(root.resolved).forEach { (name, path) ->
                    val selected = PreparedPath.open(path, native).also { owned[name] = it }
                    require(selected.resolved == path) { "Exploded descendant changed into a symlink: $path" }
                }
                return ExplodedArtifact(root, owned.toMap(), treeDigest(owned)).also { it.validate() }
            } catch (failure: Throwable) {
                var primary = failure
                owned.values.toList().asReversed().forEach {
                    try {
                        it.close()
                    } catch (cleanup: Throwable) {
                        primary = CleanupFailures.merge(primary, cleanup)
                    }
                }
                throw primary
            }
        }

        private fun list(root: Path): Map<String, Path> =
            Files.walk(root, MAX_DEPTH + 1).use { stream ->
            val paths = linkedMapOf<String, Path>()
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path == root) continue
                val relative = root.relativize(path)
                require(relative.nameCount <= MAX_DEPTH && paths.size < ArtifactBytes.MAX_ENTRIES) { "Exploded deployment exceeds bounded tree budget" }
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                require(!attributes.isSymbolicLink && (attributes.isDirectory || attributes.isRegularFile)) { "Exploded deployment contains unsupported descendant: $path" }
                require(path.toRealPath() == path) { "Exploded deployment descendant traverses a symlink: $path" }
                paths[relative.toString()] = path
            }
            paths.toSortedMap()
        }

        private fun treeDigest(entries: Map<String, PreparedPath>): String {
            var total = 0L
            val records = entries.toSortedMap().map { (name, path) ->
                ArtifactBytes.requireEntryName(name)
                if (path.directory) {
                    "directory\u0000$name"
                } else {
                    val size = Files.size(path.descriptorPath())
                    total += size
                    require(total <= ArtifactBytes.MAX_ARTIFACT_BYTES) { "Exploded deployment exceeds bounded byte budget" }
                    "file\u0000$name\u0000$size\u0000${ArtifactBytes.digest(path.descriptorPath(), size)}"
                }
            }
            return ArtifactBytes.hash(records.joinToString("\n").toByteArray())
        }
    }
}
