package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class TierETrustworthyV5EvidenceTest {
    @TempDir lateinit var output: Path

    @Test
    fun `host binds all rows to target manifest installed programs and claimed counts`() {
        val variant = "dictionary_full_stream_collector_continuous"
        val results = output.resolve("results.jsonl")
        Files.writeString(
            results,
            """{"kind":"metadata","iterations":1}
{"kind":"fork-output","variant":"$variant","fork":1,"target_pid":4320}
{"kind":"summary","sample_count":0}
{"kind":"terminal","success":true}
""",
        )
        Files.writeString(output.resolve("manifest.json"), """{"source_revision":"${"f".repeat(40)}","source_fingerprint":"${"e".repeat(64)}"}""")
        val declared = "root=/workload\nfd=7746\n"
        val oracle = output.resolve("declared-ids-$variant-fork-1.manifest")
        Files.writeString(oracle, declared)
        val row = "7746\t1\t1\t1\t1\t1\t1\t1\n"
        val sidecar = output.resolve("pre-gate-$variant-fork-1.tsv")
        val log = Files.createDirectory(output.resolve("child-logs")).resolve("$variant-fork-1.log")

        fun publish(rowText: String) {
            val content = "schema_version=5\ttarget_tgid=4321\tarm=shared-after-map-configuration\trows=1\t" +
                "declared_sha256=${sha(declared)}\tmain_program_sha256=${"b".repeat(64)}\traw_program_sha256=${"c".repeat(64)}\n" +
                "fd\tdeclared\traw_before_session\tmain_before_session\traw_after_session\tmain_after_session\traw_target_openat\tmain_exact_submitted\n" +
                rowText
            Files.writeString(sidecar, content)
            Files.writeString(
                log,
                "tier-e-kernel-smoke: exact_admission_audit_contract_version=5 workload_pid=4321 " +
                "declared_ids_evidence_file=/artifacts/${oracle.fileName} declared_ids_evidence_sha256=${sha(declared)} declared_ids_evidence_rows=1 " +
                "pre_gate_audit_evidence_file=/artifacts/${sidecar.fileName} pre_gate_audit_evidence_sha256=${sha(content)} pre_gate_audit_evidence_rows=1 " +
                "sys_enter_program_sha256=${"b".repeat(64)} sys_enter_audit_program_sha256=${"c".repeat(64)} " +
                "sys_enter_main_program_execution=IdentityOnly(programId=321, sys_enter_audit_program_execution=IdentityOnly(programId=322, " +
                "raw_sys_enter_audit_observed_operations=1 exact_admission_audit_submitted=1 " +
                "pre_gate_audit_raw_before_session=1 pre_gate_audit_main_before_session=1 " +
                "pre_gate_audit_raw_after_session=1 pre_gate_audit_main_after_session=1 " +
                "pre_gate_audit_boundary=PASS paired_early_target_audit_verdict=PASS " +
                "pre_gate_audit_failure_counts=3:0,4:0,5:0,6:0,7:0,8:0\n",
            )
        }
        publish(row)
        assertTrue(TierETrustworthyResultVerifier.verify(results, preGateRequired = true))
        Files.writeString(log, Files.readString(log).replace("programId=322", "programId=321"))
        assertThrows(IllegalStateException::class.java) { TierETrustworthyResultVerifier.verify(results, preGateRequired = true) }
        publish(row.replace("7746\t1", "7748\t1")) // valid checksum, wrong declared ID
        assertThrows(IllegalStateException::class.java) { TierETrustworthyResultVerifier.verify(results, preGateRequired = true) }
        publish(row.replace("\t1\t1\n", "\t0\t1\n")) // valid checksum, contradicts the raw receipt
        assertThrows(IllegalStateException::class.java) { TierETrustworthyResultVerifier.verify(results, preGateRequired = true) }
    }

    private fun sha(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
}
