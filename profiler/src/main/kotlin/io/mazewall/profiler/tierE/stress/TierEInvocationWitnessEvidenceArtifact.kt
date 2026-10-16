package io.mazewall.profiler.tierE.stress

import io.mazewall.profiler.tierE.engine.TierEBenchmarkAuditMapReader.InvocationWitnessCount
import io.mazewall.profiler.tierE.engine.TierEPreGateAuditCount
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** Independently bound earliest raw/main marker-ID checkpoints for each original declared FD. */
internal object TierEInvocationWitnessEvidenceArtifact {
    private const val MAX_BYTES = 16L * 1024L * 1024L
    private const val FIRST_FAILURE_KEY = 9
    private const val LAST_FAILURE_KEY = 14

    data class Receipt(
        val path: Path,
        val rows: Int,
        val sha256: String,
    )

    fun write(
        path: Path,
        targetPid: Int,
        nativeIds: Map<Int, Long>,
        tokenCounts: Map<Long, InvocationWitnessCount>,
        fdCounts: Map<Int, TierEPreGateAuditCount>,
        oracleSha256: String,
        nativeSha256: String,
        rawProgramSha256: String,
        mainProgramSha256: String,
        failures: Map<Int, Long>,
    ): Receipt {
        check(targetPid > 0 && failures.keys == (FIRST_FAILURE_KEY..LAST_FAILURE_KEY).toSet() && failures.values.all { it >= 0 }) {
            "invocation witness failure counters are incomplete"
        }
        check(listOf(oracleSha256, nativeSha256, rawProgramSha256, mainProgramSha256).all { it.matches(Regex("[a-f0-9]{64}")) }) {
            "invocation witness needs original oracle, native and both loaded BPF digests"
        }
        val stages = TierEInvocationWitnessComparison.classify(nativeIds, tokenCounts, fdCounts)
        val parent = requireNotNull(path.parent)
        check(Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.exists(path, NOFOLLOW_LINKS)) {
            "invocation witness requires fresh owned artifact destination"
        }
        val temporary = Files.createTempFile(parent, ".invocation-witness-", ".tmp")
        try {
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                writer.appendLine(
                    "schema_version=1\ttarget_tgid=$targetPid\trows=${nativeIds.size}\tdeclared_sha256=$oracleSha256\t" +
                        "native_sha256=$nativeSha256\traw_program_sha256=$rawProgramSha256\tmain_program_sha256=$mainProgramSha256\t" +
                        "failure_counts=${failures.toSortedMap().entries.joinToString(",") { (key, count) -> "$key:$count" }}",
                )
                writer.appendLine("fd\tinvocation_id\traw_token\tmain_token\traw_before_session\tmain_before_session\tearliest_stage")
                for (fd in nativeIds.keys.sorted()) {
                    val id = nativeIds.getValue(fd)
                    val token = tokenCounts[id] ?: InvocationWitnessCount(0, 0)
                    val counts = fdCounts.getValue(fd)
                    writer.appendLine("$fd\t$id\t${token.raw}\t${token.main}\t${counts.rawBeforeSession}\t${counts.mainBeforeSession}\t${stages.getValue(fd)}")
                }
            }
            check(Files.size(temporary) <= MAX_BYTES) { "invocation witness artifact exceeded bounded size" }
            Files.move(temporary, path, ATOMIC_MOVE)
            return Receipt(path, nativeIds.size, TierEPreGateEvidenceArtifact.sha256(path))
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
