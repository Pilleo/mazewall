package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierEDirectContainerArmTest {
    @Test
    fun `timed out direct arm removes only its exact named container`() {
        val directory = Files.createTempDirectory("tier-e-direct-timeout-")
        val commands = mutableListOf<List<String>>()
        try {
            val status = TierEDirectContainerArm.run(
                ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock"),
                "fixture",
                directory,
                directory,
                1000,
                1000,
                0,
                "baseline",
                100,
                "syscall_dense",
                2,
            ) { command, _, _ ->
                commands += command
                when {
                    "run" in command -> ContainerCommandResult(-1, "container endpoint probe timed out")
                    "inspect" in command -> ContainerCommandResult(1, "No such container")
                    else -> ContainerCommandResult(0, "removed")
                }
            }
            assertEquals(124, status)
            val name = commands[0][commands[0].indexOf("--name") + 1]
            assertTrue(name.startsWith("tier-e-matched-"))
            assertEquals(listOf("rm", "-f", name), commands[1].takeLast(3))
            assertEquals(listOf("inspect", name), commands[2].takeLast(2))
            assertEquals(3, commands.size)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
