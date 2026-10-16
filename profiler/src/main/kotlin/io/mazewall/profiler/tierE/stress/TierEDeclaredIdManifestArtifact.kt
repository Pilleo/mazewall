package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Retains the target-authored oracle, independent of every BPF audit map. */
internal object TierEDeclaredIdManifestArtifact {
    private const val MAX_MANIFEST_BYTES = 8L * 1024L * 1024L

    data class Receipt(
        val path: Path,
        val rows: Int,
        val sha256: String,
    )

    fun publish(
        source: Path,
        destination: Path,
        expected: Set<Int>,
    ): Receipt {
        require(expected.isNotEmpty() && expected.size <= TierEbpfEngine.BENCHMARK_AUDIT_MAX_DESCRIPTORS)
        require(Files.isRegularFile(source, NOFOLLOW_LINKS) && Files.size(source) <= MAX_MANIFEST_BYTES) {
            "target-authored declared-ID manifest is missing or oversized"
        }
        val ids = Files
            .readAllLines(source)
            .filter { it.startsWith("fd=") }
            .map { line -> line.removePrefix("fd=").toIntOrNull() ?: throw IllegalArgumentException("invalid target-authored descriptor") }
        require(ids.size == expected.size && ids.toSet() == expected) { "target-authored declared IDs changed or repeated" }
        val directory = requireNotNull(destination.parent) { "declared-ID evidence requires a parent" }
        require(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.exists(destination, NOFOLLOW_LINKS)) {
            "declared-ID evidence requires a fresh owned output path"
        }
        val temporary = Files.createTempFile(directory, ".tier-e-declared-", ".tmp")
        try {
            Files.copy(source, temporary, REPLACE_EXISTING)
            check(Files.size(temporary) <= MAX_MANIFEST_BYTES) { "copied target-authored declared-ID manifest exceeded bound" }
            Files.move(temporary, destination, ATOMIC_MOVE)
            return Receipt(destination, ids.size, TierEPreGateEvidenceArtifact.sha256(destination))
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
