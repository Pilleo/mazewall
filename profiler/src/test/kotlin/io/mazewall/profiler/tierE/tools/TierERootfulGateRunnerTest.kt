package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierERootfulGateRunnerTest {
    @Test
    fun `remote engine cannot receive local source or create artifact volume`() {
        val root = Files.createTempDirectory("tier-e-gate-root-")
        val output = Files.createTempDirectory("tier-e-gate-output-")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                TierERootfulGateRunner.run(root, output, ContainerEndpoint(ContainerEngine.DOCKER, "ssh://remote"), "fixture")
            }
            assertTrue(Files.list(output).use { it.findAny().isEmpty })
        } finally {
            root.toFile().deleteRecursively()
            output.toFile().deleteRecursively()
        }
    }
}
