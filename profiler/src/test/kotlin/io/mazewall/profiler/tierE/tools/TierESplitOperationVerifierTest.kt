package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierESplitOperationVerifierTest {
    @Test
    fun `duplicates affect event history but not exact operation coverage`() {
        val dir = Files.createTempDirectory("tier-e-operations-")
        try {
            Files.writeString(dir.resolve("oracle.json"), """{"expected_operations":2,"directory_fds":[40,41]}""")
            Files.writeString(
                dir.resolve("observations.json"),
                """{"registration_id":"r","session_epoch":1,"policy_eligibility":false,"exact_directory_fds":[40,40,41],"representative_only":false,"unresolved_observations":0}""",
            )
            Files.writeString(dir.resolve("terminal.json"), """{"registration_id":"r","session_epoch":1,"policy_eligibility":false,"evidence_integrity":"COMPLETE"}""")
            val result = TierESplitOperationVerifier.verify(dir.resolve("oracle.json"), dir.resolve("observations.json"), dir.resolve("terminal.json"))
            assertEquals("PASS", result.getValue("operation_coverage_verdict").toString().trim('"'))
            assertEquals("INCOMPLETE", result.getValue("event_history_verdict").toString().trim('"'))
            Files.writeString(dir.resolve("observations.json"), Files.readString(dir.resolve("observations.json")).replace("\"registration_id\":\"r\",", ""))
            Files.writeString(dir.resolve("terminal.json"), Files.readString(dir.resolve("terminal.json")).replace("\"registration_id\":\"r\",", ""))
            assertEquals(
                "UNVERIFIED",
                TierESplitOperationVerifier
                    .verify(dir.resolve("oracle.json"), dir.resolve("observations.json"), dir.resolve("terminal.json"))
                .getValue("operation_coverage_verdict")
                    .toString()
                    .trim('"'),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
