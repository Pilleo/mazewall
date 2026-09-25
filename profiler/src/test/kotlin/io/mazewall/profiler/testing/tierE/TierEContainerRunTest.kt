package io.mazewall.profiler.testing.tierE

import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import io.mazewall.testing.containers.runContainerCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

class TierEContainerRunTest {
    @Test
    fun `unresolved creation stops visible containers without exporting deleting or trusting shared marker`() {
        val artifacts = Files.createTempDirectory("tier-e-unsettled-")
        val unrelated = Files.createTempDirectory("tmp.")
        val runId = "tier-e-unsettled-123"
        val stopped = mutableSetOf<String>()
        try {
            Files.writeString(artifacts.resolve("$runId.shared-path"), "${unrelated.toRealPath()}\n")
            val owner = TierEContainerRun(ContainerEndpoint(ContainerEngine.DOCKER, null), runId, artifacts) { command, _, _ ->
                val name = command.last()
                when (command[1]) {
                    "inspect" -> ContainerCommandResult(0, "exists")
                    "stop" -> {
                        stopped += name
                        ContainerCommandResult(0, name)
                    }
                    else -> error("Unsettled creation cannot export or remove containers: $command")
                }
            }
            owner.stopForRecovery()
            assertEquals(setOf("$runId-target", "$runId-collector"), stopped)
            assertTrue(Files.exists(unrelated))
            assertTrue(Files.exists(artifacts.resolve("$runId.shared-path")))
        } finally {
            unrelated.toFile().deleteRecursively()
            artifacts.toFile().deleteRecursively()
        }
    }

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
                    "2" -> ContainerCommandResult(0, name)
                    "{{.State.Running}}" -> ContainerCommandResult(0, "false")
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
                    "2" -> ContainerCommandResult(0, name)
                    "{{.State.Running}}" -> ContainerCommandResult(0, "false")
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
                    "2" -> ContainerCommandResult(0, name)
                    "{{.State.Running}}" -> ContainerCommandResult(0, "false")
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
                    "2" -> ContainerCommandResult(0, name)
                    "{{.State.Running}}" -> ContainerCommandResult(0, "false")
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
                    "2" -> ContainerCommandResult(0, name)
                    "{{.State.Running}}" -> ContainerCommandResult(0, "false")
                    "-f" -> {
                        removed += name
                        ContainerCommandResult(0, name)
                    }
                    else -> error("unexpected command: $command")
                }
            }
            val error = assertThrows(IllegalStateException::class.java) { owner.close() }
            assertTrue(error.message!!.contains("inspect unavailable"))
            assertEquals(setOf("tier-e-both-123-collector"), removed)
            assertTrue(error.message!!.contains("retained for recovery: tier-e-both-123-target"))
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
                    "2" -> ContainerCommandResult(0, name)
                    "{{.State.Running}}" -> ContainerCommandResult(0, "false")
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
    fun `failed log export retains its stopped container while retiring other owned container`() {
        val directory = Files.createTempDirectory("tier-e-export-failed-")
        val stopped = mutableSetOf<String>()
        val removed = mutableSetOf<String>()
        var failTargetExport = true
        try {
            val runId = "tier-e-export-123"
            val owner = TierEContainerRun(ContainerEndpoint(ContainerEngine.DOCKER, null), runId, directory) { command, _, output ->
                val name = command.last()
                when (command[1]) {
                    "inspect" -> when {
                        name in removed -> ContainerCommandResult(1, "No such object")
                        "--format" in command -> ContainerCommandResult(0, if (name in stopped) "false" else "true")
                        else -> ContainerCommandResult(0, "exists")
                    }
                    "stop" -> {
                        stopped += name
                        ContainerCommandResult(0, name)
                    }
                    "logs" -> {
                        Files.writeString(requireNotNull(output), "partial log")
                        if (name.endsWith("target") && failTargetExport) {
                            ContainerCommandResult(1, "disk full")
                        } else {
                            Files.writeString(output, "full log")
                            ContainerCommandResult(0, "full log")
                        }
                    }
                    "rm" -> {
                        removed += name
                        ContainerCommandResult(0, name)
                    }
                    else -> error("unexpected command: $command")
                }
            }
            val error = assertThrows(IllegalStateException::class.java) { owner.close() }
            assertTrue(error.message!!.contains("$runId-target"))
            assertTrue(error.message!!.contains("disk full"))
            assertEquals(setOf("$runId-target", "$runId-collector"), stopped)
            assertEquals(setOf("$runId-collector"), removed)
            assertEquals("partial log", Files.readString(directory.resolve("target.owner.log")))
            failTargetExport = false
            owner.close()
            assertEquals(setOf("$runId-target", "$runId-collector"), removed)
            assertEquals("partial log", Files.readString(directory.resolve("target.owner.log")))
            Files.list(directory).use { files ->
                assertTrue(files.anyMatch { it.fileName.toString().startsWith("target.owner-") && Files.readString(it) == "full log" })
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `successful owned export removes its validated shared scratch directory`() {
        val artifacts = Files.createTempDirectory("tier-e-scratch-owner-")
        val runId = "tier-e-scratch-123"
        val scratch = Files.createTempDirectory("tier-e-owned-$runId-")
        try {
            Files.writeString(scratch.resolve("evidence"), "copied")
            Files.writeString(artifacts.resolve("$runId.shared-path"), "${scratch.toRealPath()}\n")
            val removed = mutableSetOf<String>()
            TierEContainerRun(ContainerEndpoint(ContainerEngine.DOCKER, null), runId, artifacts, ownedShared = scratch) { command, _, output ->
                val name = command.last()
                when (command[1]) {
                    "inspect" -> when {
                        name in removed -> ContainerCommandResult(1, "No such object")
                        "--format" in command -> ContainerCommandResult(0, "false")
                        else -> ContainerCommandResult(0, "exists")
                    }
                    "stop" -> ContainerCommandResult(0, name)
                    "logs" -> {
                        Files.writeString(requireNotNull(output), "full log")
                        ContainerCommandResult(0, "full log")
                    }
                    "rm" -> {
                        removed += name
                        ContainerCommandResult(0, name)
                    }
                    else -> error("unexpected command: $command")
                }
            }.close()
            assertEquals(setOf("$runId-target", "$runId-collector"), removed)
            assertTrue(!Files.exists(scratch))
            assertTrue(!Files.exists(artifacts.resolve("$runId.shared-path")))
        } finally {
            if (Files.exists(scratch)) scratch.toFile().deleteRecursively()
            artifacts.toFile().deleteRecursively()
        }
    }

    @Test
    fun `forged marker cannot redirect deletion to another same user scratch directory`() {
        val artifacts = Files.createTempDirectory("tier-e-scratch-binding-")
        val owned = Files.createTempDirectory("tier-e-owned-tier-e-binding-123-")
        val unrelated = Files.createTempDirectory("tmp.")
        val runId = "tier-e-binding-123"
        try {
            Files.writeString(artifacts.resolve("$runId.shared-path"), "${unrelated.toRealPath()}\n")
            val owner = TierEContainerRun(ContainerEndpoint(ContainerEngine.DOCKER, null), runId, artifacts, ownedShared = owned) { command, _, _ ->
                if (command[1] == "inspect") {
                    ContainerCommandResult(1, "No such object")
                } else {
                    error("no container to remove")
                }
            }
            val failure = assertThrows(IllegalStateException::class.java) { owner.close() }
            assertTrue(failure.message!!.contains("shared"))
            assertTrue(Files.exists(owned))
            assertTrue(Files.exists(unrelated))
        } finally {
            artifacts.toFile().deleteRecursively()
            owned.toFile().deleteRecursively()
            unrelated.toFile().deleteRecursively()
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
    fun `large retained output fits in a small heap when reading its bounded summary`() {
        val directory = Files.createTempDirectory("tier-e-small-heap-")
        try {
            val resultFile = directory.resolve("result.log")
            val child = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Xmx24m",
                "-cp",
                System.getProperty("java.class.path"),
                ContainerCommandBoundedHeapProbe::class.java.name,
                resultFile.toString(),
            ).redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile()).start()
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "bounded reader must finish")
            assertEquals(0, child.exitValue(), Files.readString(directory.resolve("child.log")))
            assertEquals(64L * 1024 * 1024, Files.size(resultFile))
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

object ContainerCommandBoundedHeapProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val file = Path.of(args.single())
        val result = runContainerCommand(
            listOf("bash", "-c", "truncate -s 67108864 \"\$1\"", "--", file.toString()),
            Duration.ofSeconds(5),
            outputFile = file,
        )
        check(result.exitCode == 0 && result.output.length <= 4096)
    }
}
