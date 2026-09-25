package io.mazewall.testing.containers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class ContainerizedTestRunnerOwnershipTest {
    @Test
    fun `Docker launcher runs as caller and never mounts host Gradle home`() {
        val run = launch("docker")
        assertEquals(0, run.exit, run.output)
        assertTrue(run.commands.any { it.contains("--user ${run.uid}:${run.gid}") }, run.commands.toString())
        assertTrue(run.commands.any { it.contains("--userns host") }, run.commands.toString())
        assertFalse(run.commands.any { it.contains("${System.getProperty("user.home")}/.gradle") }, run.commands.toString())
        assertTrue(run.commands.any { it.contains("GRADLE_USER_HOME=/container-gradle-home") }, run.commands.toString())
    }

    @Test
    fun `Podman launcher maps caller identity with keep-id`() {
        val run = launch("podman")
        assertEquals(0, run.exit, run.output)
        assertTrue(run.commands.any { it.contains("--userns keep-id") }, run.commands.toString())
        assertTrue(run.commands.any { it.contains("--user ${run.uid}:${run.gid}") }, run.commands.toString())
    }

    private data class LaunchResult(
        val exit: Int,
        val output: String,
        val commands: List<String>,
        val uid: String,
        val gid: String,
    )

    private fun launch(engine: String): LaunchResult {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("scripts/run_containerized_tests.sh")) }
        val scratch = Files.createTempDirectory("mazewall-launcher-test-")
        try {
            val project = scratch.resolve("project")
            Files.createDirectories(project.resolve("scripts"))
            Files.createDirectories(project.resolve("gradle/wrapper"))
            Files.createDirectories(project.resolve("infra/dev"))
            Files.copy(root.resolve("scripts/run_containerized_tests.sh"), project.resolve("scripts/run_containerized_tests.sh"))
            Files.copy(root.resolve("gradle/wrapper/gradle-wrapper.properties"), project.resolve("gradle/wrapper/gradle-wrapper.properties"))
            Files.copy(root.resolve("infra/dev/Containerfile"), project.resolve("infra/dev/Containerfile"))
            val fake = scratch.resolve(engine)
            Files.writeString(
                fake,
                """#!/bin/bash
printf '%s\n' "${'$'}*" >> "${'$'}MAZEWALL_FAKE_LOG"
case "${'$'}*" in
  *'--format'* ) printf '%s\n' false ;;
  *'image inspect'* | *'image exists'* ) exit 0 ;;
  *'run '* )
    for arg in "${'$'}@"; do
      case "${'$'}arg" in
        *':/mazewall-ownership-probe'*)
          host="${'$'}{arg%%:*}"
          printf owned > "${'$'}host/owned"
          ;;
      esac
    done
    ;;
esac
""",
            )
            fake.toFile().setExecutable(true)
            val log = scratch.resolve("commands.log")
            val env = ProcessBuilder("bash", "scripts/run_containerized_tests.sh", ":profiler:test")
                .directory(project.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "$scratch:${System.getenv("PATH")}"
                    environment()["MAZEWALL_CONTAINER_ENGINE"] = engine
                    environment()["XDG_CACHE_HOME"] = scratch.resolve("container-cache").toString()
                    environment()["MAZEWALL_FAKE_LOG"] = log.toString()
                    environment().remove("DOCKER_HOST")
                    environment().remove("CONTAINER_HOST")
                }.start()
            assertTrue(env.waitFor(15, TimeUnit.SECONDS), "runner must be bounded")

            fun id(flag: String) =
                ProcessBuilder("id", flag)
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
            return LaunchResult(
                env.exitValue(),
                env.inputStream.bufferedReader().readText(),
                if (Files.exists(log)) Files.readAllLines(log) else emptyList(),
                id("-u"),
                id("-g"),
            )
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }
}
