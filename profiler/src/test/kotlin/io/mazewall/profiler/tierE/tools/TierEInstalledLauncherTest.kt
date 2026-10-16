package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierEInstalledLauncherTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `installed distribution launches real CLI from another working directory`() {
        val version = invoke("version")
        assertEquals(0, version.first, version.second)
        assertEquals("tier-e-tools-1", version.second.trim())
        val invalid = invoke("unknown-command")
        assertEquals(2, invalid.first, invalid.second)
    }

    @Test
    fun `installed CLI resolves typed profile and rejects malformed input`() {
        val repository = Path.of("..").toAbsolutePath().normalize()
        val profile = repository.resolve("config/testing/tier-e/trustworthy.json")
        val output = directory.resolve("resolved.json")
        val result = invoke("experiment-config", profile.toString(), output.toString())
        assertEquals(0, result.first, result.second)
        assertTrue(Files.readString(output).contains("trustworthy"))
        val conflictingOutput = directory.resolve("conflicting.json")
        val conflicting = invoke(
            "experiment-config",
            profile.toString(),
            conflictingOutput.toString(),
            environmentOverrides = mapOf("TIER_E_CONTAINER_ENGINE" to "docker"),
        )
        assertTrue(conflicting.first != 0 && conflicting.second.contains("conflicts with legacy settings"), conflicting.second)
        assertFalse(Files.exists(conflictingOutput))
        val malformed = directory.resolve("malformed.json")
        Files.writeString(malformed, "{not json}")
        val refusedOutput = directory.resolve("refused.json")
        val refused = invoke("experiment-config", malformed.toString(), refusedOutput.toString())
        assertTrue(refused.first != 0, refused.second)
        assertFalse(Files.exists(refusedOutput))
    }

    private fun invoke(
        vararg args: String,
        environmentOverrides: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val launcher = Path.of("build/install/tier-e-daemon/bin/tier-e-tools").toAbsolutePath()
        assertTrue(Files.isExecutable(launcher), "Missing generated installed launcher: $launcher")
        val log = Files.createTempFile(directory, "cli-", ".log")
        val child = ProcessBuilder(listOf(launcher.toString()) + args)
            .apply {
                environment().keys.removeIf {
                    it.startsWith("MAZEWALL_TIER_E_") ||
                        it.startsWith("TIER_E_SUSTAINED_") ||
                        it in setOf("TIER_E_DOCKER_IMAGE", "TIER_E_CONTAINER_ENGINE")
                }
                environment().putAll(environmentOverrides)
            }.directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start()
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Installed launcher exceeded bound")
            return child.exitValue() to Files.readString(log)
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                check(child.waitFor(5, TimeUnit.SECONDS)) { "Installed CLI did not reap" }
            }
        }
    }
}
