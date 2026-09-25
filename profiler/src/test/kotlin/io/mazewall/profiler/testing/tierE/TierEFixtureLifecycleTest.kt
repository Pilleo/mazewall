package io.mazewall.profiler.testing.tierE

import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration

class TierEFixtureLifecycleTest {
    @Test
    fun `unresolved creation keeps source and scratch yet independently retires sentinel`() {
        val artifacts = Files.createTempDirectory("tier-e-lifecycle-")
        val runId = "tier-e-unresolved-123"
        val scratch = Files.createTempDirectory("tier-e-owned-$runId-")
        val commands = mutableListOf<String>()
        try {
            Files.writeString(artifacts.resolve("$runId.shared-path"), "${scratch.toRealPath()}\n")
            val owner = TierEContainerRun(ContainerEndpoint(ContainerEngine.DOCKER, null), runId, artifacts, ownedShared = scratch) { command, _, _ ->
                commands += command[1]
                when (command[1]) {
                    "inspect" -> ContainerCommandResult(0, "exists")
                    "stop" -> ContainerCommandResult(0, "stopped")
                    else -> error("unsettled creation cannot remove or export: $command")
                }
            }
            var sentinelRemoved = false
            val original = assertThrows(IllegalStateException::class.java) {
                TierEFixtureLifecycle(
                    owner,
                    artifacts.resolve("$runId.creation.lock"),
                    settle = { _, _ -> error("creation never quiesced") },
                    extraCleanup = { sentinelRemoved = true },
                ).run(start = { ProcessBuilder("bash", "-c", "exit 0").start() }) {
                    error("startup cancelled")
                }
            }
            assertTrue(original.message!!.contains("startup cancelled"))
            assertTrue(original.suppressed.any { it.message?.contains("creation never quiesced") == true })
            assertTrue(sentinelRemoved)
            assertEquals(listOf("inspect", "stop", "inspect", "stop"), commands)
            assertTrue(Files.exists(scratch))
        } finally {
            scratch.toFile().deleteRecursively()
            artifacts.toFile().deleteRecursively()
        }
    }
}
