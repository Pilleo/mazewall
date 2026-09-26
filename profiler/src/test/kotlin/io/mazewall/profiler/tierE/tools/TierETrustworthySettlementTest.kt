package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TierETrustworthySettlementTest {
    @Test
    fun `a missing required perf map keeps the source volume recoverable`() {
        val output = Files.createTempDirectory("tier-e-perf-retention-")
        val commands = mutableListOf<List<String>>()
        try {
            val owner = TierETrustworthyContainerOwner(
                ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock"),
                "tier-e-perf-retained",
                output,
            ) { command, _, _ ->
                commands += command
                if ("cp" in command) {
                    val stage = Path.of(command.last())
                    Files.writeString(
                        stage.resolve("results.jsonl"),
                        """{"kind":"fork-output","variant":"dictionary_full_stream","fork":1,"target_pid":31,"output":"collector_pid=31 workload_pid=32 tier-e-perf-window: pid=31"}
{"kind":"summary","sample_count":0}
{"kind":"terminal","success":true}
""",
                    )
                    Files.writeString(stage.resolve("perf-pid-tid-samples.txt"), "java 31/31 cycles: 0x1 symbol dso\n")
                }
                ContainerCommandResult(0, "ok")
            }
            owner.ownVolume()
            owner.ownRunner()
            assertThrows(java.nio.file.NoSuchFileException::class.java) {
                TierETrustworthySettlement.settle(owner) { TierETrustworthyPerfEvidence.write(output, true) }
            }
            assertTrue(commands.none { it.takeLast(3) == listOf("volume", "rm", owner.volume) })
            assertTrue(commands.any { "stop" in it })
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `post-export profiling rejection stays primary and retains exact source volume`() {
        val output = Files.createTempDirectory("tier-e-settlement-")
        val commands = mutableListOf<List<String>>()
        try {
            val owner = TierETrustworthyContainerOwner(
                ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock"),
                "tier-e-settlement",
                output,
            ) { command, _, _ ->
                commands += command
                if ("cp" in command) {
                    Files.writeString(
                        Path.of(command.last()).resolve("results.jsonl"),
                    """{"kind":"fork-output"}
{"kind":"summary","sample_count":0}
{"kind":"terminal","success":true}
""",
                    )
                }
                ContainerCommandResult(0, "ok")
            }
            owner.ownVolume()
            owner.ownRunner()
            val original = IllegalStateException("required JIT map missing")
            val thrown = assertThrows(IllegalStateException::class.java) {
                TierETrustworthySettlement.settle(owner) { throw original }
            }
            assertSame(original, thrown)
            assertTrue(commands.any { "cp" in it })
            assertTrue(commands.any { "stop" in it })
            assertTrue(commands.none { it.takeLast(3) == listOf("volume", "rm", owner.volume) })
            assertTrue(Files.readString(output.resolve("recovery.json")).contains("required JIT map missing"))
        } finally {
            output.toFile().deleteRecursively()
        }
    }
}
