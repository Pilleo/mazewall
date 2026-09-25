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
            ) { command, _, logFile ->
                commands += command
                val name = command.last()
                when (command[command.size - 2]) {
                    "inspect" -> if (name in removed) ContainerCommandResult(1, "No such object: $name") else ContainerCommandResult(0, "true")
                    "logs" -> {
                        Files.writeString(requireNotNull(logFile), "owned-log-$name")
                        ContainerCommandResult(0, "owned-log-$name")
                    }
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
            assertTrue(Files.readString(output.resolve("target.owner.log")).contains("owned-log"))
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `permission failure removing collector is surfaced`() {
        val output = Files.createTempDirectory("tier-e-failed-cleanup-")
        try {
            val owner = TierEContainerRun(ContainerEndpoint(ContainerEngine.DOCKER, null), "tier-e-failed-123", output) { command, _, logFile ->
                val name = command.last()
                when (command[command.size - 2]) {
                    "inspect" -> ContainerCommandResult(0, "true")
                    "logs" -> {
                        Files.writeString(requireNotNull(logFile), "log")
                        ContainerCommandResult(0, "log")
                    }
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
    fun `interrupted owner still reaps both exact containers and restores interrupt status`() {
        val directory = Files.createTempDirectory("tier-e-interrupted-owner-")
        val removed = mutableSetOf<String>()
        try {
            val owner = TierEContainerRun(ContainerEndpoint(ContainerEngine.DOCKER, null), "tier-e-cancelled-123", directory) { command, _, logFile ->
                if (Thread.currentThread().isInterrupted) return@TierEContainerRun ContainerCommandResult(-1, "container command interrupted")
                val name = command.last()
                when (command[command.size - 2]) {
                    "inspect" -> if (name in removed) ContainerCommandResult(1, "No such object") else ContainerCommandResult(0, "exists")
                    "logs" -> {
                        Files.writeString(requireNotNull(logFile), "retained")
                        ContainerCommandResult(0, "retained")
                    }
                    "-f" -> {
                        removed += name
                        ContainerCommandResult(0, name)
                    }
                    else -> error("unexpected command: $command")
                }
            }
            Thread.currentThread().interrupt()
            owner.close()
            assertEquals(setOf("tier-e-cancelled-123-target", "tier-e-cancelled-123-collector"), removed)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `interruption during inspection retries cleanup instead of skipping a live container`() {
        val directory = Files.createTempDirectory("tier-e-mid-interrupt-")
        val removed = mutableSetOf<String>()
        var firstInspect = true
        try {
            val owner = TierEContainerRun(ContainerEndpoint(ContainerEngine.DOCKER, null), "tier-e-mid-123", directory) { command, _, logFile ->
                val name = command.last()
                when (command[command.size - 2]) {
                    "inspect" -> {
                        if (firstInspect) {
                            firstInspect = false
                            Thread.currentThread().interrupt()
                            ContainerCommandResult(-1, "container command interrupted")
                        } else if (name in removed) {
                            ContainerCommandResult(1, "No such object")
                        } else {
                            ContainerCommandResult(0, "exists")
                        }
                    }
                    "logs" -> {
                        Files.writeString(requireNotNull(logFile), "full log")
                        ContainerCommandResult(0, "full log")
                    }
                    "-f" -> {
                        removed += name
                        ContainerCommandResult(0, name)
                    }
                    else -> error("unexpected command: $command")
                }
            }
            owner.close()
            assertEquals(setOf("tier-e-mid-123-target", "tier-e-mid-123-collector"), removed)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `one container inspection exception cannot suppress cleanup of the other`() {
        val directory = Files.createTempDirectory("tier-e-independent-cleanup-")
        val removed = mutableSetOf<String>()
        try {
            val owner = TierEContainerRun(ContainerEndpoint(ContainerEngine.DOCKER, null), "tier-e-both-123", directory) { command, _, logFile ->
                val name = command.last()
                when (command[command.size - 2]) {
                    "inspect" -> {
                        if (name.endsWith("target") && name !in removed) throw IllegalStateException("inspect unavailable")
                        if (name in removed) ContainerCommandResult(1, "No such object") else ContainerCommandResult(0, "exists")
                    }
                    "logs" -> {
                        Files.writeString(requireNotNull(logFile), "log")
                        ContainerCommandResult(0, "log")
                    }
                    "-f" -> {
                        removed += name
                        ContainerCommandResult(0, name)
                    }
                    else -> error("unexpected command: $command")
                }
            }
            val error = assertThrows(IllegalStateException::class.java) { owner.close() }
            assertTrue(error.message!!.contains("inspect unavailable"))
            assertEquals(setOf("tier-e-both-123-target", "tier-e-both-123-collector"), removed)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `owner retains complete logs with trailing diagnostics and preserves partial snapshots`() {
        val directory = Files.createTempDirectory("tier-e-full-logs-")
        try {
            val existing = directory.resolve("target.log")
            Files.writeString(existing, "partial shell snapshot")
            val tail = "fatal-at-end-of-log"
            val full = "x".repeat(10_000) + tail
            val removed = mutableSetOf<String>()
            val owner = TierEContainerRun(ContainerEndpoint(ContainerEngine.DOCKER, null), "tier-e-logs-123", directory) { command, _, logFile ->
                val name = command.last()
                when (command[command.size - 2]) {
                    "inspect" -> if (name in removed) ContainerCommandResult(1, "No such object") else ContainerCommandResult(0, "exists")
                    "logs" -> {
                        Files.writeString(requireNotNull(logFile), full)
                        ContainerCommandResult(0, full.take(4096))
                    }
                    "-f" -> {
                        removed += name
                        ContainerCommandResult(0, name)
                    }
                    else -> error("unexpected command: $command")
                }
            }
            owner.close()
            assertEquals("partial shell snapshot", Files.readString(existing))
            assertTrue(Files.readString(directory.resolve("target.owner.log")).endsWith(tail))
            assertTrue(Files.readString(directory.resolve("collector.owner.log")).endsWith(tail))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `command retains full output in caller owned file beyond summary limit`() {
        val directory = Files.createTempDirectory("tier-e-command-log-")
        try {
            val log = directory.resolve("complete.log")
            val result = runContainerCommand(
                listOf("bash", "-c", "printf '%010000d'; printf 'tail-diagnostic'"),
                Duration.ofSeconds(5),
                outputFile = log,
            )
            assertEquals(0, result.exitCode)
            assertTrue(result.output.length <= 4096)
            assertTrue(Files.readString(log).endsWith("tail-diagnostic"))
        } finally {
            directory.toFile().deleteRecursively()
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
