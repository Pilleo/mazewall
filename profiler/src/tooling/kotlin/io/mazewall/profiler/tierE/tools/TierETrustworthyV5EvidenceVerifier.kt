package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Reconcile the target-authored oracle, both installed programs and every declared-ID stage. */
internal object TierETrustworthyV5EvidenceVerifier {
    private const val MAX_DESCRIPTORS = 131_072
    private const val MAX_MANIFEST_BYTES = 8L * 1024L * 1024L
    private const val COLUMN_HEADER = "fd\tdeclared\traw_before_session\tmain_before_session\traw_after_session\tmain_after_session\traw_target_openat\tmain_exact_submitted"
    private const val EXPECTED_COLUMNS = 8

    fun verify(
        output: Path,
        fork: JsonObject,
        report: String,
        sidecar: Path,
        rowCount: Int,
        workloadPid: Int,
        expectedOperations: Int?,
    ) {
        check(expectedOperations != null && expectedOperations == rowCount && rowCount in 1..MAX_DESCRIPTORS) {
            "version-five fork did not retain the whole declared operation denominator"
        }

        fun field(key: String): String =
            Regex("(?:^|\\s)${Regex.escape(key)}=([^\\s]+)")
            .find(report)
                ?.groupValues
                ?.get(1) ?: error("missing version-five receipt field $key")
        val variant = fork.getValue("variant").jsonPrimitive.content
        val index = fork
            .getValue("fork")
            .jsonPrimitive.content
            .toInt()
        val oracle = output.resolve("declared-ids-$variant-fork-$index.manifest")
        check(Files.isRegularFile(oracle, NOFOLLOW_LINKS) && Files.size(oracle) <= MAX_MANIFEST_BYTES) {
            "missing target-authored declared-ID manifest"
        }
        val oracleName = Path.of(field("declared_ids_evidence_file"))
        check(
            oracleName.parent == Path.of("/artifacts") &&
            oracleName.fileName == oracle.fileName &&
            field("declared_ids_evidence_sha256") == TierETrustworthyPreGateExport.sha256(oracle) &&
            field("declared_ids_evidence_rows").toIntOrNull() == rowCount,
        ) {
            "target-authored oracle provenance or checksum changed"
        }
        val declared = Files
            .readAllLines(oracle)
            .filter { it.startsWith("fd=") }
            .map { it.removePrefix("fd=").toIntOrNull() ?: error("invalid target-authored descriptor") }
        check(declared.size == rowCount && declared.toSet().size == rowCount && declared.all { it in 0 until MAX_DESCRIPTORS }) {
            "target-authored descriptor set is incomplete or duplicated"
        }
        val digest = Regex("[a-f0-9]{64}")
        val mainSha = field("sys_enter_program_sha256")
        val rawSha = field("sys_enter_audit_program_sha256")
        val mainId = programId(report, "sys_enter_main_program_execution")
        val rawId = programId(report, "sys_enter_audit_program_execution")
        check(
            mainSha.matches(digest) &&
            rawSha.matches(digest) &&
            mainSha != rawSha &&
                mainId > 0 &&
                rawId > 0 &&
                mainId != rawId,
        ) {
            "the installed BPF program identities are unavailable"
        }

        fun verifyMisses(
            program: String,
            fieldName: String,
        ) {
            val before = field("sys_enter_${fieldName}_recursion_misses_before").toLongOrNull()
            val delta = field("sys_enter_${fieldName}_recursion_misses_delta").toLongOrNull()
            val after = Regex("${Regex.escape(program)}=(?:Available|IdentityOnly)\\([^)]*recursionMisses=([0-9]+)\\)")
                .find(report)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
            check(
                before != null &&
                delta != null &&
                after != null &&
                before >= 0 &&
                delta >= 0 &&
                Math.addExact(before, delta) == after,
            ) { "version-five $fieldName recursion-miss evidence is unavailable or inconsistent" }
        }
        verifyMisses("sys_enter_main_program_execution", "main")
        verifyMisses("sys_enter_audit_program_execution", "audit")
        val sums = LongArray(EXPECTED_COLUMNS - 2)
        val ids = HashSet<Int>(rowCount)
        Files.newBufferedReader(sidecar).use { reader ->
            val header = reader.readLine().orEmpty().split('\t')
            check(
                "schema_version=5" in header &&
                "target_tgid=$workloadPid" in header &&
                "rows=$rowCount" in header &&
                "arm=shared-after-map-configuration" in header &&
                "declared_sha256=${field("declared_ids_evidence_sha256")}" in header &&
                "main_program_sha256=$mainSha" in header &&
                "raw_program_sha256=$rawSha" in header,
            ) {
                "version-five sidecar is not bound to oracle, workload and installed program bytes"
            }
            check(reader.readLine() == COLUMN_HEADER) { "version-five per-ID columns changed" }
            var previous = -1
            repeat(rowCount) {
                val parts = reader.readLine()?.split('\t') ?: error("truncated version-five per-ID evidence")
                val fd = parts.firstOrNull()?.toIntOrNull()
                check(parts.size == EXPECTED_COLUMNS && fd != null && fd in 0 until MAX_DESCRIPTORS && fd > previous && parts[1] == "1") {
                    "missing, reordered or duplicated version-five descriptor"
                }
                ids.add(fd)
                previous = fd
                val values = parts.drop(2).map { it.toLongOrNull() ?: error("invalid version-five counter") }
                values.forEachIndexed { position, value ->
                    check(value == 1L) { "version-five per-ID stage is incomplete: fd=$fd stage=$position value=$value" }
                    sums[position] = Math.addExact(sums[position], value)
                }
            }
            check(reader.readLine() == null) { "extra version-five per-ID rows" }
        }
        check(ids == declared.toSet()) { "sidecar descriptor IDs differ from the original target-authored oracle" }
        val reported = listOf(
            "pre_gate_audit_raw_before_session",
            "pre_gate_audit_main_before_session",
            "pre_gate_audit_raw_after_session",
            "pre_gate_audit_main_after_session",
            "raw_sys_enter_audit_observed_operations",
            "exact_admission_audit_submitted",
        )
        check(reported.indices.all { field(reported[it]).toLongOrNull() == sums[it] }) {
            "version-five reported raw/main totals do not match the complete per-ID artifact"
        }
        check(
            field("pre_gate_audit_boundary") == "PASS" &&
            field("paired_early_target_audit_verdict") == "PASS" &&
            field("pre_gate_audit_failure_counts") == "3:0,4:0,5:0,6:0,7:0,8:0",
        ) {
            "version-five audit cannot publish an incomplete success terminal"
        }
        val hostManifest = Json.parseToJsonElement(Files.readString(output.resolve("manifest.json"))).jsonObject
        check(
            hostManifest
                .getValue("source_fingerprint")
                .jsonPrimitive.content
                .matches(digest) &&
            hostManifest
                .getValue("source_revision")
                .jsonPrimitive.content
                .matches(Regex("[a-f0-9]{40}")),
        ) {
            "source-bound host manifest is absent from version-five export"
        }
    }

    private fun programId(
        report: String,
        key: String,
    ): Int =
        Regex("${Regex.escape(key)}=(?:Available|IdentityOnly)\\(programId=([0-9]+)")
            .find(report)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull() ?: 0
}
