package io.mazewall.profiler.testing.tierE

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class TierEContainerizedRunnerOwnershipTest {
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
            assertFalse(commands.any { it.contains("volume rm") }, "the only artifact volume must survive export failure")
            assertTrue(
                process.inputStream
                .bufferedReader()
                .readText()
                .contains("artifact volume"),
            )
            assertEquals(Files.getOwner(output), Files.getOwner(output.resolve("runner-events.jsonl")))
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Tier E gate export cannot report success after host copy fails`() {
        val script = Files.readString(repo().resolve("scripts/run_tier_e_rootful_gate.sh"))
        val function = script.substringAfter("export_artifacts() {").substringBefore("\ncleanup() {")
        assertExportFails("export_artifacts() {$function", "if ! export_artifacts; then echo EXPORT_FAILED; else echo FALSE_SUCCESS; fi")
    }

    @Test
    fun `a stale passing gate artifact cannot turn an empty export into success`() {
        val root = repo()
        val scratch = Files.createTempDirectory("tier-e-stale-gate-")
        try {
            val fake = scratch.resolve("docker")
            Files.writeString(
                fake,
                """#!/bin/bash
case "${'$'}*" in
  *'info '* ) printf '["name=seccomp"]' ;;
  *'run --rm -i'* ) cat >/dev/null ;;
  *'volume inspect'* ) exit 1 ;;
  *'volume create'* ) printf volume ;;
  *'create --name'* ) printf container ;;
  *'logs '* ) printf retained-log ;;
esac
""",
            )
            fake.toFile().setExecutable(true)
            val output = scratch.resolve("evidence")
            Files.createDirectories(output)
            Files.writeString(output.resolve("benchmark-gate-status.txt"), "PASS\n")
            val run = ProcessBuilder("bash", root.resolve("scripts/run_tier_e_rootful_gate.sh").toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "$scratch:${System.getenv("PATH")}"
                    environment()["TIER_E_ARTIFACT_DIR"] = output.toString()
                }.start()
            assertTrue(run.waitFor(30, TimeUnit.SECONDS))
            assertTrue(run.exitValue() != 0, "a prior PASS cannot substitute for this run's artifact")
            assertEquals("PASS\n", Files.readString(output.resolve("benchmark-gate-status.txt")))
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    @Test
    fun `benchmark rejects old results before starting a new privileged run`() {
        val root = repo()
        val scratch = Files.createTempDirectory("tier-e-stale-benchmark-")
        try {
            val fake = scratch.resolve("docker")
            Files.writeString(
                fake,
                """#!/bin/bash
printf '%s\n' "${'$'}*" >> "${'$'}MAZEWALL_FAKE_LOG"
exit 0
""",
            )
            fake.toFile().setExecutable(true)
            val output = scratch.resolve("evidence")
            Files.createDirectories(output)
            Files.writeString(output.resolve("results.jsonl"), "old-result\n")
            val log = scratch.resolve("commands.log")
            val run = ProcessBuilder("bash", root.resolve("scripts/run_tier_e_trustworthy_benchmark.sh").toString(), output.toString())
                .directory(root.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] = "$scratch:${System.getenv("PATH")}"
                    environment()["MAZEWALL_FAKE_LOG"] = log.toString()
                    environment()["MAZEWALL_TIER_E_BENCHMARK_CPUS"] = "0,1"
                }.start()
            assertTrue(run.waitFor(30, TimeUnit.SECONDS))
            assertTrue(run.exitValue() != 0)
            assertFalse(Files.exists(log), "must refuse stale results before contacting the engine")
            assertEquals("old-result\n", Files.readString(output.resolve("results.jsonl")))
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    @Test
    fun `benchmark export cannot mark evidence published after host copy fails`() {
        val script = Files.readString(repo().resolve("scripts/run_tier_e_trustworthy_benchmark.sh"))
        val function = script.substringAfter("export_container_artifacts() {").substringBefore("\nrunner_event() {")
        assertExportFails("export_container_artifacts() {$function", "export_container_artifacts || echo EXPORT_FAILED")
    }

    @ParameterizedTest
    @ValueSource(strings = ["staging", "engine-copy", "validation", "publication", "staging-cleanup"])
    fun `both privileged exporters fail closed at every publication stage`(scenario: String) {
        for ((scriptName, method) in listOf(
            "run_tier_e_rootful_gate.sh" to "export_artifacts",
            "run_tier_e_trustworthy_benchmark.sh" to "export_container_artifacts",
        )) {
            val script = Files.readString(repo().resolve("scripts/$scriptName"))
            val suffix = if (method == "export_artifacts") "\ncleanup() {" else "\nrunner_event() {"
            val function = "$method() {${script.substringAfter("$method() {").substringBefore(suffix)}"
            val bootstrap = """set -euo pipefail
SCENARIO=$scenario
ARTIFACT_DIR=/virtual
ARTIFACT_VOLUME=owned-volume
ARTIFACT_EXPORTED=false
RUN_ID=owned-run
CONTAINER=owned-container
IMAGE=owned-image
ENGINE=(engine)
DOCKER=(engine)
engine() {
  if [[ "${'$'}SCENARIO" == engine-copy && "${'$'}1" == cp ]]; then return 1; fi
  return 0
}
mktemp() { if [[ "${'$'}SCENARIO" == staging ]]; then return 1; fi; printf '/virtual/stage\n'; }
find() { if [[ "${'$'}SCENARIO" == validation ]]; then return 1; fi; return 0; }
cp() { if [[ "${'$'}SCENARIO" == publication ]]; then return 1; fi; return 0; }
rm() { if [[ "${'$'}SCENARIO" == staging-cleanup ]]; then return 1; fi; return 0; }
rmdir() { return 0; }
$function
if $method; then printf 'FALSE_SUCCESS\n'; else printf 'EXPORT_FAILED\n'; fi
"""
            val proc = ProcessBuilder("bash").redirectErrorStream(true).start()
            proc.outputStream.bufferedWriter().use { it.write(bootstrap) }
            assertTrue(proc.waitFor(5, TimeUnit.SECONDS))
            val output = proc.inputStream.bufferedReader().readText()
            assertTrue(output.contains("EXPORT_FAILED"), "$scriptName/$scenario: $output")
            assertFalse(output.contains("FALSE_SUCCESS"), "$scriptName/$scenario: $output")
        }
    }

    private fun assertExportFails(
        function: String,
        invocation: String,
    ) {
        val code = """set -euo pipefail
ARTIFACT_DIR=/virtual
ARTIFACT_VOLUME=owned-volume
ARTIFACT_EXPORTED=false
RUN_ID=owned-run
CONTAINER=owned-container
IMAGE=owned-image
ENGINE=(engine)
DOCKER=(engine)
engine() { return 0; }
mktemp() { printf '/virtual/stage\n'; }
find() { return 0; }
cp() { printf 'simulated export copy failure\n' >&2; return 1; }
rm() { return 0; }
rmdir() { return 0; }
$function
$invocation
"""
        val run = ProcessBuilder("bash").redirectErrorStream(true).start()
        run.outputStream.bufferedWriter().use { it.write(code) }
        assertTrue(run.waitFor(5, TimeUnit.SECONDS))
        val output = run.inputStream.bufferedReader().readText()
        assertTrue(output.contains("EXPORT_FAILED"), output)
        assertFalse(output.contains("FALSE_SUCCESS"), output)
    }

    private fun repo(): Path =
        generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("scripts/run_tier_e_rootful_gate.sh")) }
}
