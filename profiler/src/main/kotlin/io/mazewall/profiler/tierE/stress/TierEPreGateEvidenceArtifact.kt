package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEPreGateAuditCount
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat

/** Complete benchmark-only descriptor evidence; stdout previews never replace this artifact. */
internal object TierEPreGateEvidenceArtifact {
    private const val MAX_BYTES = 64L * 1024L * 1024L

    data class Receipt(
        val path: Path,
        val rows: Int,
        val sha256: String,
    )

    fun write(
        path: Path,
        targetPid: Int,
        expected: Set<Int>,
        counts: Map<Int, TierEPreGateAuditCount>,
        rawTarget: Map<Int, Long>,
        mainSubmitted: Map<Int, Long>,
    ): Receipt {
        require(targetPid > 0 && expected.isNotEmpty() && expected.size <= TierEbpfEngine.BENCHMARK_AUDIT_MAX_DESCRIPTORS)
        require(expected.all { it in 0 until TierEbpfEngine.BENCHMARK_AUDIT_MAX_DESCRIPTORS })
        require(counts.keys == expected && rawTarget.keys == expected && mainSubmitted.keys == expected) {
            "pre-gate evidence must contain every declared ID in every counter domain"
        }
        val directory = requireNotNull(path.parent) { "pre-gate evidence requires a parent directory" }
        require(Files.isDirectory(directory, NOFOLLOW_LINKS) && !Files.exists(path, NOFOLLOW_LINKS)) {
            "pre-gate evidence requires an existing owned output directory and a fresh destination"
        }
        val temporary = Files.createTempFile(directory, ".pre-gate-evidence-", ".tmp")
        try {
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8, TRUNCATE_EXISTING).use { output ->
                output.appendLine("schema_version=4\ttarget_tgid=$targetPid\tarm=shared-after-map-configuration\trows=${expected.size}")
                output.appendLine("fd\tdeclared\traw_before_session\tmain_before_session\traw_after_session\tmain_after_session\traw_target_openat\tmain_exact_submitted")
                for (fd in expected.sorted()) {
                    val count = counts.getValue(fd)
                    output.appendLine(
                        "$fd\t${count.declared}\t${count.rawBeforeSession}\t${count.mainBeforeSession}\t" +
                        "${count.rawAfterSession}\t${count.mainAfterSession}\t${rawTarget.getValue(fd)}\t${mainSubmitted.getValue(fd)}",
                    )
                }
            }
            check(Files.size(temporary) <= MAX_BYTES) { "pre-gate evidence exceeds bounded artifact size" }
            Files.move(temporary, path, ATOMIC_MOVE)
            return Receipt(path, expected.size, sha256(path))
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}
