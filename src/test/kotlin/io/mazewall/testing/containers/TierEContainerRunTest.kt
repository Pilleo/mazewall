package io.mazewall.testing.containers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration

class TierEContainerRunTest {
    @Test
    fun `controller cleanup targets only its allocated target and collector`() {
        val commands = mutableListOf<List<String>>()
        val removed = mutableSetOf<String>()
        val output = Files.createTempDirectory("tier-e-run-owner-")
        try {
            val owner = TierEContainerRun(
                ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock"),
                "tier-e-owned-123",
                output,
            ) { command, _ ->
                commands += command
                val name = command.last()
                when (command[command.size - 2]) {
                    "inspect" -> if (name in removed) ContainerCommandResult(1, "No such object: $name") else ContainerCommandResult(0, "true")
                    "logs" -> ContainerCommandResult(0, "owned-log-$name")
                    "-f" -> {
                        removed += name
                        ContainerCommandResult(0, name)
                    }
                    else -> ContainerCommandResult(2, "unexpected command: $command")
                }
            }
            owner.close()
            assertEquals(setOf("tier-e-owned-123-target", "tier-e-owned-123-collector"), removed)
            assertTrue(commands.none { it.joinToString(" ").contains("unrelated-sentinel") })
            assertTrue(Files.readString(output.resolve("target.log")).contains("owned-log"))
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `permission failure removing collector is surfaced`() {
        val output = Files.createTempDirectory("tier-e-failed-cleanup-")
        try {
            val owner = TierEContainerRun(ContainerEndpoint(ContainerEngine.DOCKER, null), "tier-e-failed-123", output) { command, _ ->
                val name = command.last()
                when (command[command.size - 2]) {
                    "inspect" -> ContainerCommandResult(0, "true")
                    "logs" -> ContainerCommandResult(0, "log")
                    "-f" -> if (name.endsWith("collector")) ContainerCommandResult(13, "permission denied") else ContainerCommandResult(0, name)
                    else -> ContainerCommandResult(2, "unexpected")
                }
            }
            val error = assertThrows(IllegalStateException::class.java) { owner.close() }
            assertTrue(error.message!!.contains("collector"))
            assertTrue(error.message!!.contains("permission denied"))
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `timeout first offers owned controller a graceful TERM cleanup window`() {
        val directory = Files.createTempDirectory("tier-e-controller-term-")
        try {
            val marker = directory.resolve("term-received")
            val controller = "trap 'printf term > $marker; exit 0' TERM; while :; do :; done"
            val result = runContainerCommand(listOf("bash", "-c", controller), Duration.ofMillis(250))
            assertEquals(-1, result.exitCode)
            assertTrue(Files.exists(marker), "controller must see TERM before force-kill")
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
