package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierETrustworthyContainerOwnerTest {
    @Test
    fun `interrupted recovery temporarily permits stopping owned runner then restores interrupt`() {
        val output = Files.createTempDirectory("tier-e-interrupted-recovery-")
        var stopWasUninterrupted = false
        try {
            val owner = TierETrustworthyContainerOwner(
                ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock"),
                "tier-e-interrupted",
                output,
            ) { command, _, _ ->
                if ("stop" in command) stopWasUninterrupted = !Thread.currentThread().isInterrupted
                ContainerCommandResult(0, "ok")
            }
            owner.ownRunner()
            Thread.currentThread().interrupt()
            owner.retainForRecovery("cancelled")
            assertTrue(stopWasUninterrupted)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `post export evidence failure retains stopped runner and source volume`() {
        val output = Files.createTempDirectory("tier-e-owner-post-export-")
        val commands = mutableListOf<List<String>>()
        try {
            val owner = TierETrustworthyContainerOwner(
                ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock"),
                "tier-e-post-export",
                output,
            ) { command, _, _ ->
                commands += command
                if ("cp" in command) {
                    Files.writeString(
                        java.nio.file.Path
                            .of(command.last())
                            .resolve("results.jsonl"),
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
            owner.export()
            assertTrue(commands.none { "rm" in it })
            owner.retainForRecovery("perf map missing")
            assertTrue(commands.any { "stop" in it })
            assertTrue(commands.none { it.takeLast(3) == listOf("volume", "rm", "tier-e-post-export-artifacts") })
            assertTrue(Files.readString(output.resolve("recovery.json")).contains("perf map missing"))
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `custom rootful gate verdict failure retains copied runner and workspace`() {
        val output = Files.createTempDirectory("tier-e-owner-verdict-")
        val calls = mutableListOf<List<String>>()
        try {
            val owner = TierETrustworthyContainerOwner(
                ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock"),
                "tier-e-unqualified",
                output,
                verifyExport = { error("native workflow skipped") },
            ) { command, _, _ ->
                calls += command
                if ("cp" in command) {
                    Files.writeString(
                        java.nio.file.Path
                    .of(command.last())
                    .resolve("native-ctest.log"),
                        "native",
                    )
                }
                ContainerCommandResult(0, "ok")
            }
            owner.ownVolume()
            owner.ownRunner()
            assertThrows(IllegalStateException::class.java) { owner.close() }
            assertTrue(calls.any { "stop" in it })
            assertTrue(calls.none { it.takeLast(3) == listOf("volume", "rm", "tier-e-unqualified-artifacts") })
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `volume without a created benchmark runner is retired on setup failure`() {
        val output = Files.createTempDirectory("tier-e-owner-prelaunch-")
        val calls = mutableListOf<List<String>>()
        try {
            val owner = TierETrustworthyContainerOwner(
                ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock"),
                "tier-e-prelaunch",
                output,
            ) { command, _, _ ->
                calls += command
                ContainerCommandResult(0, "removed")
            }
            owner.ownVolume()
            owner.close()
            assertTrue(calls.any { it.takeLast(3) == listOf("volume", "rm", "tier-e-prelaunch-artifacts") })
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed artifact copy retains exact volume and stopped runner for recovery`() {
        val output = Files.createTempDirectory("tier-e-owner-recovery-")
        val calls = mutableListOf<List<String>>()
        try {
            val owner = TierETrustworthyContainerOwner(
                ContainerEndpoint(ContainerEngine.DOCKER, "unix:///var/run/docker.sock"),
                "tier-e-owned-test",
                output,
            ) { command, _, _ ->
                calls += command
                if ("cp" in command) ContainerCommandResult(1, "copy failed") else ContainerCommandResult(0, "ok")
            }
            owner.ownVolume()
            owner.ownRunner()
            assertThrows(IllegalStateException::class.java) { owner.close() }
            assertTrue(calls.any { it.takeLast(3) == listOf("stop", "-t", "2") || "stop" in it })
            assertTrue(calls.none { it.takeLast(2) == listOf("rm", "tier-e-owned-test-artifacts") })
            assertTrue(Files.readString(output.resolve("recovery.json")).contains("RETAINED_FOR_RECOVERY"))
        } finally {
            output.toFile().deleteRecursively()
        }
    }
}
