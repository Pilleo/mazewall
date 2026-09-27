package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class TierETrustworthyResultVerifierTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `result admission requires exact terminal summary and sample cardinality`() {
        val file = Files.createTempFile("tier-e-trust-results-", ".jsonl")
        try {
            Files.writeString(
                file,
                """{"kind":"fork-output"}
{"kind":"sample"}
{"kind":"summary","sample_count":1}
{"kind":"terminal","success":true}
""",
            )
            assertTrue(TierETrustworthyResultVerifier.verify(file))
            Files.writeString(file, Files.readString(file).replace("\"sample_count\":1", "\"sample_count\":2"))
            assertThrows(IllegalArgumentException::class.java) { TierETrustworthyResultVerifier.verify(file) }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `version four export requires matching complete per ID evidence before source retirement`() {
        val results = directory.resolve("results.jsonl")
        val variant = "dictionary_full_stream_collector_continuous"
        Files.writeString(
            results,
            """{"kind":"fork-output","variant":"$variant","fork":1,"target_pid":4320}
{"kind":"summary","sample_count":0}
{"kind":"terminal","success":true}
""",
        )
        val sidecar = directory.resolve("pre-gate-$variant-fork-1.tsv")
        val logs = Files.createDirectory(directory.resolve("child-logs")).resolve("$variant-fork-1.log")
        val contents = "schema_version=4\ttarget_tgid=4321\tarm=shared-after-map-configuration\trows=1\n" +
            "fd\tdeclared\traw_before_session\tmain_before_session\traw_after_session\tmain_after_session\traw_target_openat\tmain_exact_submitted\n" +
            "7746\t1\t0\t1\t0\t1\t0\t1\n"
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents.toByteArray()))
        Files.writeString(
            logs,
            "exact_admission_audit_contract_version=4 pre_gate_audit_evidence_rows=1 " +
            "pre_gate_audit_evidence_file=/artifacts/${sidecar.fileName} pre_gate_audit_evidence_sha256=$hash workload_pid=4321\n",
        )
        assertThrows(IllegalStateException::class.java) { TierETrustworthyResultVerifier.verify(results, preGateRequired = true) }
        Files.writeString(sidecar, contents)
        assertTrue(TierETrustworthyResultVerifier.verify(results, preGateRequired = true))
        val wrongPid = contents.replace("target_tgid=4321", "target_tgid=9876")
        val wrongHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wrongPid.toByteArray()))
        Files.writeString(sidecar, wrongPid)
        Files.writeString(logs, Files.readString(logs).replace(hash, wrongHash))
        assertThrows(IllegalStateException::class.java) { TierETrustworthyResultVerifier.verify(results, preGateRequired = true) }
        Files.writeString(logs, Files.readString(logs).replace(wrongHash, hash))
        Files.writeString(sidecar, contents.replace("7746\t1\t0", "7746\t1\t1"))
        assertThrows(IllegalStateException::class.java) { TierETrustworthyResultVerifier.verify(results, preGateRequired = true) }
    }
}
