package io.mazewall.profiler.tierE.tools

import io.mazewall.profiler.tierE.tools.controller.TierENativeFixtureBuild
import io.mazewall.profiler.tierE.tools.controller.TierESplitFixtureController
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.runContainerCommand
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Duration

/** Short matched rotation. It records diagnostics and does not change promotion policy. */
public object TierEMatchedMatrix {
    public val defaultModes: List<String> = listOf(
        "baseline",
        "dictionary-full-stream",
        "dictionary-unique-edge",
        "inline-full-stream",
        "inline-unique-edge",
        "user-notif-no-stack",
        "user-notif-stack",
    )

    public fun plan(
        forks: Int,
        modes: List<String>,
    ): List<Pair<Int, String>> {
        require(forks > 0 && modes.isNotEmpty())
        return (0 until forks).flatMap { fork ->
            modes.indices.map { step -> fork to modes[(fork + step) % modes.size] }
        }
    }

    public fun run(
        config: TierERunConfiguration,
        endpoint: ContainerEndpoint,
        root: Path,
        output: Path,
        forks: Int,
        iterations: Int,
        modes: List<String>,
        parity: String,
        workloadProfile: String,
    ) {
        require(iterations > 0 && parity in setOf("even", "odd", "all"))
        require(modes.all { it in defaultModes || it.contains("-v") || it.endsWith("-hybrid") })
        Files.createDirectories(output)
        require(Files.list(output).use { it.findAny().isEmpty }) { "Tier E matched matrix requires a fresh directory" }
        val before = TierESourceFingerprint.read(root, null)
        Files.writeString(output.resolve("source-state-before.json"), "$before\n")
        val native = TierENativeFixtureBuild.prepare(root, config.build.nativeBuildDir)
        val uid = Files.getAttribute(root, "unix:uid") as Int
        val gid = Files.getAttribute(root, "unix:gid") as Int
        var failures = 0
        for ((fork, mode) in plan(forks, modes)) {
            val artifact = output.resolve("fork-$fork-$mode")
            Files.createDirectories(artifact)
            val status = if (mode in setOf("baseline", "user-notif-no-stack", "user-notif-stack")) {
                direct(endpoint, config, root, artifact, uid, gid, mode, iterations, workloadProfile)
            } else {
                fixture(config, endpoint, root, artifact, native, fork, mode, iterations, parity, workloadProfile)
            }
            val row = TierESplitForkRow.parse(fork, mode, status, artifact, 10, iterations * 10)
            Files.writeString(output.resolve("fork-results.jsonl"), "$row\n", CREATE, APPEND)
            if (row.getValue("fork_complete") != JsonPrimitive(true)) failures += 1
        }
        val after = TierESourceFingerprint.read(root, before)
        Files.writeString(output.resolve("source-state-after.json"), "$after\n")
        val drift = if (after["unchanged"] == JsonPrimitive(true)) 0 else 1
        val summary = TierESplitMatchedSummary.summarize(output, failures, drift)
        Files.writeString(output.resolve("summary.json"), "$summary\n")
        val success = (summary.getValue("terminal") as kotlinx.serialization.json.JsonObject).getValue("success") == JsonPrimitive(true)
        check(success) { "Matched matrix incomplete: $summary" }
    }

    private fun direct(
        endpoint: ContainerEndpoint,
        config: TierERunConfiguration,
        root: Path,
        artifact: Path,
        uid: Int,
        gid: Int,
        mode: String,
        iterations: Int,
        workloadProfile: String,
    ): Int {
        val result = runContainerCommand(
            endpoint.command(
                "run",
                "--rm",
                "--user",
                "$uid:$gid",
                "--cap-drop",
                "ALL",
                "--cpuset-cpus",
                config.measurement.workloadCpu.toString(),
                "-e",
                "JAVA_TOOL_OPTIONS=-Dmazewall.tier-e.workload-profile=$workloadProfile",
                "-v",
                "$root:/workspace:ro",
                "-w",
                "/tmp",
                config.container.image,
                "java",
                "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                "/workspace/profiler/build/install/tier-e-daemon/lib/*",
                "io.mazewall.profiler.tierE.stress.TierESplitBenchmarkTarget",
                mode,
                "3",
                "10",
                iterations.toString(),
            ),
            Duration.ofSeconds(config.deadlines.armSeconds.toLong()),
            outputFile = artifact.resolve("target.log"),
        )
        return result.exitCode
    }

    private fun fixture(
        config: TierERunConfiguration,
        endpoint: ContainerEndpoint,
        root: Path,
        artifact: Path,
        native: Path,
        fork: Int,
        mode: String,
        iterations: Int,
        parity: String,
        workloadProfile: String,
    ): Int {
        val transport = if (mode.startsWith("inline-")) "inline-per-invocation" else "dictionary"
        val emission = if ("unique-edge" in mode) "unique-stack-syscall" else "full-stream"
        val marker = if (mode.endsWith("-v2") || mode.endsWith("-hybrid")) "v2" else "v1"
        val recovery = if (mode.endsWith("-hybrid")) "recover" else "disabled"
        val phases = parity == "all" || parity == "even" && fork % 2 == 0 || parity == "odd" && fork % 2 == 1
        val status = try {
            TierESplitFixtureController.run(
                config.copy(
                    fixture = config.fixture.copy(coverage = false, emissionMode = emission, recoveryMode = recovery),
                    measurement = config.measurement.copy(iterations = iterations),
                    scenario = config.scenario.copy(
                        markerVersion = marker,
                        stackTransport = transport,
                        benchmark = true,
                        baseline = false,
                        faultSyscall = null,
                        negativeArtifact = null,
                        identityCase = null,
                    ),
                ),
                endpoint,
                root,
                artifact,
                native,
                23063 + fork,
                pinCpus = true,
                benchmarkPhases = phases,
                workloadProfile = workloadProfile,
            )
            0
        } catch (failure: Exception) {
            val uniqueEdgeOracle = emission == "unique-stack-syscall" &&
                failure.message.orEmpty().contains("Benchmark operation oracle incomplete")
            Files.writeString(artifact.resolve("runner-failure.txt"), failure.stackTraceToString())
            if (uniqueEdgeOracle) 0 else 1
        }
        Files.writeString(artifact.resolve("instrumented.env"), "instrumented=${if (phases) 1 else 0}\n")
        return status
    }
}
