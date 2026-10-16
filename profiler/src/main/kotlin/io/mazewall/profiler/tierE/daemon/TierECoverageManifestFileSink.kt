package io.mazewall.profiler.tierE.daemon

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Writes the benchmark-only coverage projection with the same path-safety contract as terminal evidence. */
internal class TierECoverageManifestFileSink(
    root: Path,
) {
    private val root = root.toAbsolutePath().normalize()

    init {
        require(Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) { "Tier E evidence root must be an existing directory" }
        require(!Files.isSymbolicLink(this.root)) { "Tier E evidence root must not be a symbolic link" }
    }

    fun accept(manifest: TierECoverageManifest) {
        val file = root.resolve("${manifest.registrationId}-${manifest.sessionEpoch}.tier-e-coverage.json").normalize()
        require(file.parent == root) { "coverage manifest escaped evidence root" }
        val temporary = Files.createTempFile(root, ".${manifest.registrationId}-${manifest.sessionEpoch}.", ".tmp")
        try {
            Files.writeString(temporary, manifest.toJson(), StandardCharsets.UTF_8)
            try {
                Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
