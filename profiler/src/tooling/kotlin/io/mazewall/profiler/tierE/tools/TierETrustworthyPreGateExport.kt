package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Verify every byte of the child-published version-four sidecar before retiring its source volume. */
internal object TierETrustworthyPreGateExport {
    private const val MAX_BYTES = 64L * 1024L * 1024L
    private const val MAX_DESCRIPTORS = 131_072
    private const val COLUMN_HEADER = "fd\tdeclared\traw_before_session\tmain_before_session\traw_after_session\tmain_after_session\traw_target_openat\tmain_exact_submitted"

    fun verify(
        output: Path,
        fork: JsonObject,
    ) {
        val variant = fork["variant"]?.jsonPrimitive?.content.orEmpty()
        val index = fork["fork"]?.jsonPrimitive?.intOrNull
        val collectorPid = fork["target_pid"]?.jsonPrimitive?.intOrNull
        check(variant.matches(Regex("[a-z0-9_-]+")) && index != null && index > 0 && collectorPid != null && collectorPid > 0) {
            "invalid Tier E fork sidecar identity"
        }
        val name = "pre-gate-$variant-fork-$index.tsv"
        val log = output.resolve("child-logs/$variant-fork-$index.log")
        check(Files.isRegularFile(log, NOFOLLOW_LINKS)) { "missing version-four fork log: $name" }
        val line = Files.readAllLines(log).singleOrNull { "exact_admission_audit_contract_version=4" in it }
            ?: error("missing version-four diagnostic receipt for $name")

        fun field(key: String): String =
            Regex("(?:^|\\s)${Regex.escape(key)}=([^\\s]+)")
            .find(line)
                ?.groupValues
                ?.get(1) ?: error("missing version-four receipt field $key")
        val workloadPid = field("workload_pid").toIntOrNull()
        check(workloadPid != null && workloadPid > 0) { "version-four receipt lacks the admitted workload PID" }
        val published = Path.of(field("pre_gate_audit_evidence_file"))
        check(published.parent == Path.of("/artifacts") && published.fileName.toString() == name) {
            "version-four receipt did not bind the expected owned sidecar"
        }
        val file = output.resolve(name)
        check(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= MAX_BYTES) { "missing or oversized version-four sidecar" }
        val rows = field("pre_gate_audit_evidence_rows").toIntOrNull()
        check(rows != null && rows in 1..MAX_DESCRIPTORS) { "invalid version-four sidecar row count" }
        check(sha256(file) == field("pre_gate_audit_evidence_sha256")) { "version-four sidecar hash mismatch" }
        Files.newBufferedReader(file).use { reader ->
            val header = reader.readLine().orEmpty().split('\t')
            check(
                "schema_version=4" in header &&
                "arm=shared-after-map-configuration" in header &&
                "target_tgid=$workloadPid" in header &&
                "rows=$rows" in header,
            ) {
                "version-four sidecar header disagrees with fork receipt"
            }
            check(reader.readLine() == COLUMN_HEADER) { "version-four sidecar columns changed" }
            var previousFd = -1
            repeat(rows) {
                val values = reader.readLine()?.split('\t') ?: error("truncated version-four sidecar")
                val fd = values.firstOrNull()?.toIntOrNull()
                check(values.size == 8 && fd != null && fd in 0 until MAX_DESCRIPTORS && fd > previousFd && values[1] == "1") {
                    "invalid or duplicate version-four descriptor row"
                }
                check(values.drop(2).all { it.toLongOrNull() != null }) { "invalid version-four descriptor counter" }
                previousFd = fd
            }
            check(reader.readLine() == null) { "extra version-four descriptor rows" }
        }
    }

    private fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
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
