package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Independently bind an audit-disabled comparison success to its original target-authored ID set. */
internal object TierETrustworthyComparisonVerifier {
    private const val MAX_MANIFEST_BYTES = 8L * 1024L * 1024L
    private const val MAX_DECLARED_FD = 131_072

    fun verify(
        output: Path,
        fork: JsonObject,
        expected: Int?,
        hostManifest: JsonObject,
    ) {
        val config = hostManifest.getValue("configuration") as? JsonObject ?: error("missing comparison configuration")
        val variant = fork.getValue("variant").jsonPrimitive.content
        val index = fork
            .getValue("fork")
            .jsonPrimitive.content
            .toIntOrNull()
        check(variant.matches(Regex("[a-z0-9_-]+")) && index != null && index > 0 && expected != null && expected > 0) {
            "comparison lacks an exact expected fork identity"
        }
        val cpu = hostManifest["workload_cpu"]?.jsonPrimitive?.content ?: error("comparison lacks selected workload CPU")
        val rawAudit = config
            .getValue("benchmark_raw_sys_enter_audit")
            .jsonPrimitive.content
            .toBooleanStrict()
        val exactAudit = config
            .getValue("benchmark_exact_admission_audit")
            .jsonPrimitive.content
            .toBooleanStrict()
        val options = config.getValue("benchmark_java_options").jsonPrimitive.content
        check(
            config.getValue("scope").jsonPrimitive.content == "tier-e-sustained-collector" &&
            config.getValue("workload").jsonPrimitive.content == "syscall_dense" &&
            hostManifest.getValue("policy_eligibility").jsonPrimitive.content == "false" &&
            rawAudit == exactAudit &&
            (!rawAudit || "-Dmazewall.tier-e.benchmark-invocation-id-witness=true" in options),
        ) { "comparison profile escaped the pinned audit-disabled/witness modes" }
        val original = output.resolve("declared-ids-$variant-fork-$index.manifest")
        check(Files.isRegularFile(original, NOFOLLOW_LINKS) && Files.size(original) <= MAX_MANIFEST_BYTES) {
            "comparison has no bounded target-authored FD manifest"
        }
        val lines = Files.readAllLines(original)
        val fds = lines.drop(1).map { line ->
            check(line.startsWith("fd=")) { "invalid target-authored comparison FD row" }
            line.removePrefix("fd=").toIntOrNull() ?: error("invalid comparison FD")
        }
        check(
            lines.firstOrNull()?.startsWith("root=") == true &&
            fds.size == expected &&
            fds.toSet().size == expected &&
            fds.all { it in 0 until MAX_DECLARED_FD },
        ) { "comparison lacks every distinct target-authored declared ID" }
        val log = output.resolve("child-logs/$variant-fork-$index.log")
        check(Files.isRegularFile(log, NOFOLLOW_LINKS)) { "comparison lacks exact fork log" }
        val entries = Files.readAllLines(log)
        val affinity = entries.singleOrNull { it.startsWith("tier-e-pre-gate-affinity: ") }
        val selected = affinity?.let {
            Regex("tier-e-pre-gate-affinity: workload_cpu=([0-9]+) threads=([1-9][0-9]*) launched_pinned=true").matchEntire(it)
        }
        check(selected != null && selected.groupValues[1] == cpu) { "comparison did not pin every target JVM thread before gate release" }
        val report = entries.singleOrNull { it.startsWith("tier-e-kernel-smoke: ") }
            ?: error("comparison lacks unique target report")

        fun field(key: String): String =
            Regex("(?:^|\\s)${Regex.escape(key)}=([^\\s]+)").findAll(report).map { it.groupValues[1] }.singleOrNull()
                ?: error("comparison missing or repeated receipt field $key")
        check(
            field("sustained_comparison_contract_version") == "1" &&
            field("sustained_comparison_declared") == "PASS" &&
            field("sustained_comparison_diagnostic") == (if (rawAudit) "PASS" else "DISABLED") &&
            field("sustained_comparison_qualification") == "UNVERIFIED" &&
            field("operation_oracle_available") == "true" &&
            field("operation_oracle_complete") == "true" &&
            field("expected_operations").toIntOrNull() == expected &&
            field("matched_operations").toIntOrNull() == expected &&
            listOf("missing_operations", "duplicate_operations", "wrong_operations", "unresolved_operations", "unclassified_operations")
                .all { field(it) == "0" } &&
            field("declared_ids_evidence_file") == "/artifacts/${original.fileName}" &&
            field("declared_ids_evidence_rows").toIntOrNull() == expected &&
            field("declared_ids_evidence_sha256") == TierETrustworthyPreGateExport.sha256(original),
        ) { "comparison source/ID/CPU/verdict domains do not independently reconcile" }
    }
}
