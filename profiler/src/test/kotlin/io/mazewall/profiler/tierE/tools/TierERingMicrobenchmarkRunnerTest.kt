package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierERingMicrobenchmarkRunnerTest {
    @Test
    fun `mapped drain counts must agree with the declared per-drain variant`() {
        val results = Files.createTempFile("tier-e-ring-micro-", ".jsonl")
        try {
            Files.writeString(
                results,
                """{"kind":"sample","variant":"empty","drains":2,"records":0}
{"kind":"sample","variant":"single","drains":2,"records":2}
{"kind":"sample","variant":"batch-16","drains":2,"records":32}
""",
            )
            assertTrue(TierERingMicrobenchmarkRunner.verify("mapped-drain", results, 1, 1))
            Files.writeString(results, Files.readString(results).replace("\"records\":32", "\"records\":31"))
            assertThrows(IllegalArgumentException::class.java) {
                TierERingMicrobenchmarkRunner.verify("mapped-drain", results, 1, 1)
            }
        } finally {
            Files.deleteIfExists(results)
        }
    }
}
