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

    @Test
    fun `privileged Tier E gate uses only engine-owned writable volumes and exports logs after failure`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("scripts/run_tier_e_rootful_gate.sh")) }
        val scratch = Files.createTempDirectory("mazewall-gate-test-")
        try {
            val fake = scratch.resolve("docker")
            Files.writeString(
                fake,
                """#!/bin/bash
printf '%s\n' "${'$'}*" >> "${'$'}MAZEWALL_FAKE_LOG"
case "${'$'}*" in
  *'info '* ) printf '["name=seccomp"]' ;;
  *'run --rm -i'* ) cat >/dev/null ;;
  *'volume inspect'* ) exit 1 ;;
  *'volume create'* ) printf volume ;;
  *'create --name'* ) printf container ;;
  *'start -a'* ) exit 19 ;;
  *'logs '* ) printf retained-log ;;
esac
""",
            )
            fake.toFile().setExecutable(true)
            val log = scratch.resolve("commands.log")
            val output = scratch.resolve("evidence")
            val process = ProcessBuilder("bash", "scripts/run_tier_e_rootful_gate.sh")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "$scratch:${System.getenv("PATH")}"
                    environment()["TIER_E_ARTIFACT_DIR"] = output.toString()
                    environment()["MAZEWALL_FAKE_LOG"] = log.toString()
                }.start()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "fixture launch must be bounded")
            assertTrue(process.exitValue() != 0, "failed privileged fixture must stay failed")
            val commands = Files.readAllLines(log)
            assertTrue(commands.any { it.contains("create --name") }, "commands=$commands output=${process.inputStream.bufferedReader().readText()}")
            val create = commands.first { it.contains("create --name") }
            assertTrue(create.contains("--privileged"))
            assertTrue(create.contains(":/workspace"), create)
            assertTrue(create.contains(":/container-gradle-home"), create)
            assertFalse(create.contains("$root:$root"), create)
            assertFalse(create.contains("${System.getProperty("user.home")}/.gradle"), create)
            assertTrue(commands.any { it.contains("cp ") }, "failure must attempt artifact export")
            assertTrue(Files.readString(output.resolve("rootful-gate.log")).contains("retained-log"))
            assertEquals(Files.getOwner(output), Files.getOwner(output.resolve("rootful-gate.log")))
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rootful Podman gate uses isolated workspace and Gradle volume`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("scripts/run_tier_e_rootful_gate.sh")) }
        val scratch = Files.createTempDirectory("mazewall-podman-gate-")
        try {
            val fake = scratch.resolve("podman")
            Files.writeString(
                fake,
                """#!/bin/bash
printf '%s\n' "${'$'}*" >> "${'$'}MAZEWALL_FAKE_LOG"
case "${'$'}*" in
  *'info '* ) printf false ;;
  *'run --rm -i'* ) cat >/dev/null ;;
  *'volume inspect'* ) exit 1 ;;
  *'volume create'* ) printf volume ;;
  *'create --name'* ) printf container ;;
  *'start -a'* ) exit 19 ;;
  *'logs '* ) printf retained-log ;;
esac
""",
            )
            fake.toFile().setExecutable(true)
            val log = scratch.resolve("commands.log")
            val process = ProcessBuilder("bash", "scripts/run_tier_e_rootful_gate.sh")
                .directory(root.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "$scratch:${System.getenv("PATH")}"
                    environment()["TIER_E_CONTAINER_ENGINE"] = "podman"
                    environment()["TIER_E_ARTIFACT_DIR"] = scratch.resolve("evidence").toString()
                    environment()["MAZEWALL_FAKE_LOG"] = log.toString()
                }.start()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            val commands = Files.readAllLines(log)
            assertTrue(commands.any { it.contains("create --name") && it.contains("--privileged") }, commands.toString())
            assertFalse(commands.any { it.contains("$root:$root") || it.contains("${System.getProperty("user.home")}/.gradle") })
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed privileged benchmark never mounts writable host artifacts`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("scripts/run_tier_e_trustworthy_benchmark.sh")) }
        val scratch = Files.createTempDirectory("mazewall-benchmark-ownership-")
        try {
            val fake = scratch.resolve("docker")
            Files.writeString(
                fake,
                """#!/bin/bash
printf '%s\n' "${'$'}*" >> "${'$'}MAZEWALL_FAKE_LOG"
case "${'$'}*" in
  *'image inspect'* ) printf 'sha256:fake' ;;
  *':/ownership-probe'* )
    for arg in "${'$'}@"; do case "${'$'}arg" in *':/ownership-probe')
      host="${'$'}{arg%%:*}"; printf owned > "${'$'}host/owned" ;;
    esac; done ;;
  *'volume inspect'* ) exit 1 ;;
  *'volume create'* ) printf volume ;;
  *'run --rm --privileged'* ) exit 43 ;;
  *'create --name'* ) printf container ;;
  *'cp '* ) exit 1 ;;
esac
""",
            )
            fake.toFile().setExecutable(true)
            val log = scratch.resolve("commands.log")
            val output = scratch.resolve("evidence")
            val process = ProcessBuilder("bash", "scripts/run_tier_e_trustworthy_benchmark.sh", output.toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "$scratch:${System.getenv("PATH")}"
                    environment()["MAZEWALL_FAKE_LOG"] = log.toString()
                    environment()["MAZEWALL_TIER_E_BENCHMARK_CPUS"] = "0,1"
                }.start()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "benchmark failure should be bounded")
            assertTrue(process.exitValue() != 0)
            val commands = Files.readAllLines(log)
            assertTrue(commands.any { it.contains("run --rm --privileged") }, commands.toString())
            assertFalse(commands.any { it.contains("$output:/artifacts") }, commands.toString())
            assertFalse(commands.any { it.contains("${System.getProperty("user.home")}/.gradle") })
            assertTrue(commands.any { it.contains("volume rm") }, "run-scoped artifact volume must be retired")
            assertEquals(Files.getOwner(output), Files.getOwner(output.resolve("runner-events.jsonl")))
        } finally {
            scratch.toFile().deleteRecursively()
        }
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
            // A minimal caller-owned checkout keeps this policy test independent
            // of old root-owned native build output in the developer checkout.
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
            val cache = scratch.resolve("container-cache")
            val env = ProcessBuilder("bash", "scripts/run_containerized_tests.sh", ":profiler:test")
                .directory(project.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "$scratch:${System.getenv("PATH")}"
                    environment()["MAZEWALL_CONTAINER_ENGINE"] = engine
                    environment()["XDG_CACHE_HOME"] = cache.toString()
                    environment()["MAZEWALL_FAKE_LOG"] = log.toString()
                    environment().remove("DOCKER_HOST")
                    environment().remove("CONTAINER_HOST")
                }.start()
            assertTrue(env.waitFor(15, TimeUnit.SECONDS), "runner must be bounded")
            return LaunchResult(
                env.exitValue(),
                env.inputStream.bufferedReader().readText(),
                if (Files.exists(log)) Files.readAllLines(log) else emptyList(),
                ProcessBuilder("id", "-u")
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
                    .trim(),
                ProcessBuilder("id", "-g")
                    .start()
                    .inputStream
                    .bufferedReader()
                    .readText()
                    .trim(),
            )
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }
}
