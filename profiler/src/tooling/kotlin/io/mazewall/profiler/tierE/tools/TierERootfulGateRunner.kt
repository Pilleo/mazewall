package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.runContainerCommand
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Rootful, disposable source-volume test gate with exact-name ownership and fail-closed exports. */
public object TierERootfulGateRunner {
    public fun profilerVerificationCommand(runner: String): List<String> =
        listOf(
        "exec",
        "-e",
        "TIER_E_AGENT_LIBRARY=/workspace/profiler-native/build/libmazewall_stack_agent.so",
        "-e",
        "TIER_E_WORKLOAD_CLASSPATH=/workspace/profiler-native/build/agent-test-classes",
        runner,
        "./gradlew",
        ":profiler:test",
        ":profiler:integrationTest",
        ":profiler:integrationTestFreshJvm",
        "-Dtier.e.agent=/workspace/profiler-native/build/libmazewall_stack_agent.so",
        "-Dtier.e.workloadClasspath=/workspace/profiler-native/build/agent-test-classes",
        "--no-daemon",
        "--stacktrace",
    )

    public fun run(
        root: Path,
        output: Path,
        endpoint: ContainerEndpoint,
        image: String,
    ) {
        require(endpoint.resolvedUri?.startsWith("unix://") == true) { "Tier E rootful gate requires a local engine" }
        Files.createDirectories(output)
        require(Files.list(output).use { it.findAny().isEmpty }) { "Tier E gate requires fresh artifacts" }
        val repository = root.toRealPath()
        val artifacts = output.toRealPath()
        val id = "tier-e-rootful-gate-${UUID.randomUUID()}"
        val owner = TierETrustworthyContainerOwner(endpoint, id, artifacts, verifyExport = { TierERootfulGateResultVerifier.verify(it) })

        fun docker(
            vararg args: String,
            timeout: Duration = Duration.ofMinutes(5),
            log: Path? = null,
        ): String {
            val result = runContainerCommand(endpoint.command(*args), timeout, outputFile = log)
            check(result.exitCode == 0) { "Tier E gate Docker command failed: ${args.take(4)} (${result.exitCode}): ${result.output}" }
            return result.output.trim()
        }
        val containerfile = repository.resolve("infra/dev/Containerfile")
        val containerSha = MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(containerfile))
            .joinToString("") { "%02x".format(it) }
        val label = runContainerCommand(
            endpoint.command(
                "image",
                "inspect",
                image,
            "--format",
                "{{index .Config.Labels \"io.mazewall.containerfile.sha256\"}}",
            ),
        )
        if (label.exitCode != 0 || label.output.trim() != containerSha) {
            docker(
                "build",
                "--label",
                "io.mazewall.containerfile.sha256=$containerSha",
                "-t",
                image,
                "-f",
                containerfile.toString(),
                repository.toString(),
                timeout = Duration.ofMinutes(15),
                log = artifacts.resolve("image-build.log"),
            )
        }
        val wrapper = repository.resolve("gradle/wrapper/gradle-wrapper.properties")
        val key = MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(wrapper) + Files.readAllBytes(containerfile))
            .joinToString("") { "%02x".format(it) }
            .take(12)
        val uid = Files.getAttribute(repository, "unix:uid") as Int
        val cache = "mazewall-tier-e-rootful-gradle-jdk25-$uid-$key"
        val runnerLog = artifacts.resolve("rootful-gate.log")
        TierEMatrixTerminal.run(artifacts) {
            var primary: Throwable? = null
            try {
                check(runContainerCommand(endpoint.command("volume", "inspect", owner.volume)).exitCode != 0) {
                    "Refusing to reuse rootful gate workspace"
                }
                docker("volume", "create", owner.volume)
                owner.ownVolume()
                docker("volume", "create", cache)
                val tar = Files.createTempFile("tier-e-rootful-source-", ".tar")
                try {
                    TierERootfulSourceTransfer.archive(repository, tar)
                    val unpack = ProcessBuilder(
                        endpoint.command(
                            "run",
                            "--rm",
                            "-i",
                            "--name",
                            "$id-transfer",
                        "-v",
                            "${owner.volume}:/workspace",
                            "--entrypoint",
                            "tar",
                            image,
                            "-C",
                            "/workspace",
                            "-xf",
                            "-",
                        ),
                    ).redirectInput(tar.toFile())
                        .redirectErrorStream(true)
                        .start()
                    val message = unpack.inputStream.bufferedReader().use { it.readText() }
                    check(unpack.waitFor(5, TimeUnit.MINUTES) && unpack.exitValue() == 0) { "Source transfer failed: $message" }
                } finally {
                    Files.deleteIfExists(tar)
                    runContainerCommand(endpoint.command("rm", "-f", "$id-transfer"))
                }
                docker(
                    "create",
                    "--name",
                    owner.runner,
                    "--privileged",
                    "--pid=host",
                    "--network=host",
                    "--userns=host",
                    "--security-opt",
                    "seccomp=unconfined",
                    "-v",
                    "${owner.volume}:/workspace",
                    "-v",
                    "$cache:/container-gradle-home",
                    "-e",
                    "GRADLE_USER_HOME=/container-gradle-home",
                    "-e",
                    "TIER_E_ARTIFACT_DIR=/artifacts",
                    "-e",
                    "CI=${System.getenv("CI") ?: "false"}",
                    "-e",
                    "MAZEWALL_IN_CONTAINER=true",
                    "-w",
                    "/workspace",
                    image,
                    "sleep",
                    "7200",
                )
                owner.ownRunner()
                docker("start", owner.runner)
                docker("exec", owner.runner, "mkdir", "-p", "/artifacts")
                if (runContainerCommand(endpoint.command("exec", owner.runner, "mountpoint", "-q", "/sys/kernel/tracing")).exitCode != 0) {
                    docker("exec", owner.runner, "mount", "-t", "tracefs", "tracefs", "/sys/kernel/tracing")
                }
                docker(
                    "exec",
                    owner.runner,
                    "cmake",
                    "-S",
                    "profiler-native",
                    "-B",
                    "profiler-native/build",
                    "-DCMAKE_BUILD_TYPE=Debug",
                    timeout = Duration.ofMinutes(5),
                )
                docker("exec", owner.runner, "cmake", "--build", "profiler-native/build", "-j2", timeout = Duration.ofMinutes(5))
                docker(
                    "exec",
                    owner.runner,
                    "ctest",
                    "--test-dir",
                    "profiler-native/build",
                    "-V",
                    timeout = Duration.ofMinutes(5),
                    log = artifacts.resolve("native-ctest.log"),
                )
                docker(
                    *profilerVerificationCommand(owner.runner).toTypedArray(),
                    timeout = Duration.ofMinutes(25),
                    log = artifacts.resolve("profiler-tests.log"),
                )
                docker("exec", owner.runner, "cp", "-a", "profiler/build/test-results/integrationTestFreshJvm", "/artifacts/")
                val gate = artifacts.resolve("benchmark-gate.log")
                Files.writeString(artifacts.resolve("benchmark-gate-contract.txt"), "2\n")
                val acquisitionFailures = mutableListOf<Throwable>()
                for (mode in listOf("full", "unique")) {
                    val logs = (1..3)
                        .map { sample ->
                        val result = runContainerCommand(
                            endpoint.command(
                                "exec",
                                owner.runner,
                                "ctest",
                                "--test-dir",
                                "profiler-native/build",
                            "-V",
                                "-R",
                                "^agent_phase_comparable_$mode$",
                            ),
                                Duration.ofMinutes(3),
                            outputFile = artifacts.resolve("$mode-$sample.log"),
                        )
                        if (result.exitCode != 0) acquisitionFailures += IllegalStateException("Native benchmark failed for $mode sample $sample")
                        Files.readString(artifacts.resolve("$mode-$sample.log"))
                    }.joinToString("\n")
                    Files.writeString(gate, logs, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
                }
                val replay = TierERootfulPhaseEvidence.verify(artifacts, repository.resolve("profiler-native/tier-e-benchmark-baseline.env"))
                acquisitionFailures.forEach { replay.exceptionOrNull()?.addSuppressed(it) }
                check(replay.isSuccess && acquisitionFailures.isEmpty()) {
                    "Native phase gate incomplete: ${replay.exceptionOrNull()?.message ?: acquisitionFailures.firstOrNull()?.message}"
                }
                Files.writeString(artifacts.resolve("benchmark-gate-status.txt"), "PASS\n")
            } catch (error: Throwable) {
                primary = error
                Files.writeString(artifacts.resolve("benchmark-gate-status.txt"), "FAILED\n")
            } finally {
                try {
                    owner.finish(primary == null)
                } catch (cleanup: Throwable) {
                    if (primary == null) primary = cleanup else primary.addSuppressed(cleanup)
                }
            }
            Files.writeString(
                artifacts.resolve("run-metadata.txt"),
                buildString {
                val status = if (primary == null) "PASS" else "INCOMPLETE"
                appendLine("commit_sha=${runContainerCommand(listOf("git", "-C", repository.toString(), "rev-parse", "HEAD")).output.trim()}")
                appendLine("docker_image=$image")
                for (dimension in listOf(
                    "capture_domain_validity",
                        "transport_completeness",
                        "attribution_coverage",
                    "dictionary_integrity",
                        "lifecycle_validity",
                )) {
                        appendLine("$dimension=$status")
                    }
                appendLine("benchmark_gate_status=${if (primary == null) "PASS" else "FAILED"}")
                appendLine("benchmark_gate_contract=2")
                appendLine("rootful_gate_status=${if (primary == null) 0 else 1}")
                appendLine("rootless_podman_fixture=INCOMPLETE")
            },
            )
            if (Files.exists(artifacts.resolve("benchmark-container.log"))) {
                Files.copy(artifacts.resolve("benchmark-container.log"), runnerLog)
            }
            if (primary != null) throw primary
        }
    }
}
