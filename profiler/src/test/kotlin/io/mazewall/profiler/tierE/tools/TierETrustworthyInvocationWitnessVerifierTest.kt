package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class TierETrustworthyInvocationWitnessVerifierTest {
    @TempDir lateinit var directory: Path
    private val variant = "dictionary_full_stream_collector_continuous"
    private val oracle = "a".repeat(64)
    private val raw = "c".repeat(64)
    private val main = "b".repeat(64)

    private fun sha(path: Path): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))

    private fun receipt(
        token: Long = 1,
        fd: Int = 13,
    ): String {
        val native = directory.resolve("invocation-fd-$variant-fork-1.mzif")
        val bytes = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        bytes
            .put(byteArrayOf(77, 90, 73, 70))
            .putShort(1)
            .putShort(16)
            .putInt(0x4d5a)
            .putInt(0)
        bytes.putLong(1).putLong(0)
        bytes.putLong(0x4d5a0000002a).putInt(fd).putInt(0)
        Files.write(native, bytes.array())
        val logs = directory.resolve("child-logs")
        Files.createDirectories(logs)
        Files.writeString(logs.resolve("$variant-fork-1.log"), "tier-e-pre-gate-affinity: workload_cpu=0 threads=13 launched_pinned=true\n")
        val sidecar = directory.resolve("invocation-witness-$variant-fork-1.tsv")
        Files.writeString(
            sidecar,
            "schema_version=1\ttarget_tgid=4321\trows=1\tdeclared_sha256=$oracle\t" +
                "native_sha256=${sha(native)}\traw_program_sha256=$raw\t" +
                "main_program_sha256=$main\tfailure_counts=9:0,10:0,11:0,12:0,13:0,14:0\n" +
                "fd\tinvocation_id\traw_token\tmain_token\traw_before_session\tmain_before_session\tearliest_stage\n" +
                "$fd\t85048942395434\t$token\t1\t1\t1\tFD_CHECKPOINT_PRESENT\n",
        )
        return "invocation_witness_contract_version=1 invocation_witness_evidence_file=/artifacts/${sidecar.fileName} " +
            "invocation_witness_evidence_rows=1 invocation_witness_evidence_sha256=${sha(sidecar)} " +
            "invocation_witness_native_sha256=${sha(native)} " +
            "invocation_witness_failure_counts=9:0,10:0,11:0,12:0,13:0,14:0 invocation_witness_complete=true " +
            "raw_sys_enter_audit_cpu_counts=0:1 raw_sys_enter_audit_cpu_total=1 raw_sys_enter_audit_observed_operations=1"
    }

    @Test
    fun `host accepts an exact source-bound marker join and rejects substituted token rows`() {
        val original = receipt()
        TierETrustworthyInvocationWitnessVerifier.verify(directory, variant, 1, original, setOf(13), 4321, "0", oracle, raw, main)
        assertThrows(IllegalStateException::class.java) {
            TierETrustworthyInvocationWitnessVerifier.verify(directory, variant, 1, receipt(token = 0), setOf(13), 4321, "0", oracle, raw, main)
        }
        assertThrows(IllegalStateException::class.java) {
            TierETrustworthyInvocationWitnessVerifier.verify(directory, variant, 1, receipt(fd = 14), setOf(13), 4321, "0", oracle, raw, main)
        }
        assertThrows(IllegalStateException::class.java) {
            TierETrustworthyInvocationWitnessVerifier.verify(directory, variant, 1, original, setOf(13), 4321, "2", oracle, raw, main)
        }
    }
}
