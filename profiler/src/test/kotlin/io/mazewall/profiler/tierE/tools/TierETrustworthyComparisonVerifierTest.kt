package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class TierETrustworthyComparisonVerifierTest {
    @TempDir lateinit var output: Path
    private val variant = "dictionary_full_stream_collector_continuous"

    private fun sha(value: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    @Test
    fun `audit-disabled comparison success must retain original target IDs and distinct verdict axes`() {
        val results = output.resolve("results.jsonl")
        Files.writeString(
            results,
            """{"kind":"metadata","iterations":2}
{"kind":"fork-output","variant":"$variant","fork":1,"target_pid":4320}
{"kind":"summary","sample_count":0}
{"kind":"terminal","success":true}
""",
        )
        Files.writeString(
            output.resolve("manifest.json"),
            """{"source_revision":"${"a".repeat(
                40,
            )}","workload_cpu":"0","policy_eligibility":false,"configuration":{"scope":"tier-e-sustained-collector","workload":"syscall_dense","benchmark_java_options":"-Dmazewall.tier-e.sustained-comparison=true","benchmark_raw_sys_enter_audit":false,"benchmark_exact_admission_audit":false}}""",
        )
        val content = "root=/workload\nfd=13\nfd=14\n"
        val declared = output.resolve("declared-ids-$variant-fork-1.manifest")
        val log = Files.createDirectory(output.resolve("child-logs")).resolve("$variant-fork-1.log")
        val report = "tier-e-pre-gate-affinity: workload_cpu=0 threads=13 launched_pinned=true\n" +
            "tier-e-kernel-smoke: workload_pid=4321 sustained_comparison_contract_version=1 " +
            "sustained_comparison_declared=PASS sustained_comparison_diagnostic=DISABLED sustained_comparison_qualification=UNVERIFIED " +
            "declared_ids_evidence_file=/artifacts/${declared.fileName} declared_ids_evidence_rows=2 " +
            "declared_ids_evidence_sha256=${sha(content)} operation_oracle_available=true " +
            "expected_operations=2 matched_operations=2 missing_operations=0 duplicate_operations=0 " +
            "wrong_operations=0 unresolved_operations=0 unclassified_operations=0 operation_oracle_complete=true\n"
        Files.writeString(log, report)
        assertThrows(IllegalStateException::class.java) { TierETrustworthyResultVerifier.verify(results, preGateRequired = false) }
        Files.writeString(declared, content)
        assertTrue(TierETrustworthyResultVerifier.verify(results, preGateRequired = false))
        Files.writeString(log, report.replace("sustained_comparison_declared=PASS", "sustained_comparison_declared=INCOMPLETE"))
        assertThrows(IllegalStateException::class.java) { TierETrustworthyResultVerifier.verify(results, preGateRequired = false) }
    }
}
