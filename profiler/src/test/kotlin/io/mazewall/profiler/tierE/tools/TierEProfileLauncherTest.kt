package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TierEProfileLauncherTest {
    @Test
    fun `rootful shell keeps an explicit historical engine and image invocation`() {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("scripts/run_tier_e_rootful_gate.sh")) }
        val root = Files.createTempDirectory("tier-e-rootful-entrypoint-")
        try {
            val scripts = Files.createDirectory(root.resolve("scripts"))
            val script = scripts.resolve("run_tier_e_rootful_gate.sh")
            Files.copy(repository.resolve("scripts/run_tier_e_rootful_gate.sh"), script)
            Files.writeString(root.resolve("gradlew"), "#!/bin/sh\nexit 0\n")
            root.resolve("gradlew").toFile().setExecutable(true)
            val profile = Files.createDirectories(root.resolve("config/testing/tier-e")).resolve("rootful-gate.json")
            Files.writeString(profile, "{}\n")
            val capture = root.resolve("invoked.txt")
            Files.writeString(scripts.resolve("tier_e_tools"), "#!/bin/sh\nprintf '%s\\n' \"\$@\" > '$capture'\n")

            fun invoke(
                option: String? = null,
                image: String? = null,
            ): List<String> {
                val command = listOf("bash", script.toString()) + listOfNotNull(option)
                val child = ProcessBuilder(command)
                    .apply {
                    directory(root.toFile())
                    environment().remove("TIER_E_DOCKER_IMAGE")
                    environment().remove("TIER_E_CONTAINER_ENGINE")
                    environment()["TIER_E_ARTIFACT_DIR"] = root.resolve("artifacts").toString()
                    if (image != null) environment()["TIER_E_DOCKER_IMAGE"] = image
                }.redirectErrorStream(true)
                    .start()
                val message = child.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, child.waitFor(), message)
                return Files.readAllLines(capture)
            }
            assertEquals("rootful-gate-profile", invoke().first())
            assertEquals("rootful-gate", invoke(image = "historical-image").first())
            assertEquals("rootful-gate", invoke(option = "--legacy-env").first())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `normal shell launchers select typed profiles while declared legacy knobs preserve historical mode`() {
        val repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("scripts/run_tier_e_trustworthy_benchmark.sh")) }
        val cases = listOf(
            Triple("run_tier_e_trustworthy_benchmark.sh", "experiment-run", "trustworthy-run"),
            Triple("run_tier_e_sustained_capacity.sh", "capacity-profile", "capacity-run"),
            Triple("run_tier_e_node_noise_probe.sh", "node-noise-profile", "node-noise-run"),
        )
        for ((script, profileCommand, legacyCommand) in cases) {
            val root = Files.createTempDirectory("tier-e-profile-entrypoint-")
            try {
                val scripts = Files.createDirectory(root.resolve("scripts"))
                Files.copy(repository.resolve("scripts/$script"), scripts.resolve(script))
                val profiles = Files.createDirectories(root.resolve("config/testing/tier-e"))
                for (name in listOf("trustworthy", "capacity", "node-noise")) {
                    Files.writeString(profiles.resolve("$name.json"), "{}\n")
                }
                Files.writeString(root.resolve("gradlew"), "#!/bin/sh\nexit 0\n")
                root.resolve("gradlew").toFile().setExecutable(true)
                val capture = root.resolve("invoked.txt")
                Files.writeString(scripts.resolve("tier_e_tools"), "#!/bin/sh\nprintf '%s\\n' \"\$@\" > '$capture'\n")
                val output = root.resolve("artifacts")
                assertEquals(profileCommand, invoke(scripts.resolve(script), output, capture).first())
                assertTrue(
                    invoke(scripts.resolve(script), output, capture).any {
                    it.startsWith(root.resolve("config/testing/tier-e").toString()) && it.endsWith(".json")
                },
                )
                assertEquals(
                    legacyCommand,
                    invoke(
                        scripts.resolve(script),
                        output,
                        capture,
                    env = mapOf("MAZEWALL_TIER_E_FORKS" to "1"),
                    ).first(),
                )
                assertEquals(legacyCommand, invoke(scripts.resolve(script), output, capture, legacy = true).first())
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    }

    private fun invoke(
        script: Path,
        output: Path,
        capture: Path,
        legacy: Boolean = false,
        env: Map<String, String> = emptyMap(),
    ): List<String> {
        val command = listOf("bash", script.toString(), output.toString()) + if (legacy) listOf("--legacy-env") else emptyList()
        val process = ProcessBuilder(command)
            .apply {
            environment().keys.removeIf { it.startsWith("MAZEWALL_TIER_E_") }
            environment().putAll(env)
            directory(script.parent.parent.toFile())
        }.redirectErrorStream(true)
            .start()
        val message = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), message)
        return Files.readAllLines(capture)
    }
}
