package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierETrustworthyResultVerifierTest {
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
}
