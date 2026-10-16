package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierEArmAttemptTest {
    @Test
    fun `operation-verdict-looking exception stays a failed arm`() {
        val directory = Files.createTempDirectory("tier-e-failed-arm-")
        try {
            val status = TierEArmAttempt.run(directory) {
                throw IllegalStateException("Benchmark operation oracle incomplete: representative")
            }
            assertEquals(1, status)
            assertTrue(Files.readString(directory.resolve("runner-failure.txt")).contains("Benchmark operation oracle incomplete"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
