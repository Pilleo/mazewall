package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierETrustworthyRunnerTest {
    @Test
    fun `remote container endpoint is rejected before any benchmark artifact is created`() {
        val root = Files.createTempDirectory("tier-e-trust-root-")
        val output = Files.createTempDirectory("tier-e-trust-out-")
        try {
            val settings = TierETrustworthySettings.load(mapOf("MAZEWALL_TIER_E_RUN_LEVEL" to "smoke"))
            assertThrows(IllegalArgumentException::class.java) {
                TierETrustworthyRunner.run(
                    settings,
                    ContainerEndpoint(ContainerEngine.DOCKER, "ssh://remote"),
                    root,
                    output,
                    "fixture",
                )
            }
            assertTrue(Files.list(output).use { it.findAny().isEmpty })
        } finally {
            root.toFile().deleteRecursively()
            output.toFile().deleteRecursively()
        }
    }
}
