package io.mazewall.profiler.tierE.tools

import io.mazewall.profiler.tierE.tools.controller.TierENativeFixtureBuild
import io.mazewall.profiler.tierE.tools.controller.TierESplitFixtureController
import io.mazewall.testing.containers.ContainerEndpoint
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

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
    private val supportedModes = defaultModes.toSet() +
        setOf(
        "dictionary-full-stream-v1",
            "dictionary-full-stream-v2",
            "dictionary-full-stream-hybrid",
        "dictionary-unique-edge-v1",
            "dictionary-unique-edge-v2",
            "dictionary-unique-edge-hybrid",
    )

    public fun plan(
        forks: Int,
        modes: List<String>,
    ): List<Pair<Int, String>> {
        require(forks > 0 && modes.isNotEmpty() && modes.size == modes.toSet().size && modes.all { it in supportedModes }) {
            "Invalid Tier E matched modes"
        }
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
        Files.createDirectories(output)
        require(Files.list(output).use { it.findAny().isEmpty }) { "Tier E matched matrix requires a fresh directory" }
        val schedule = plan(forks, modes)
        TierEMatrixTerminal.run(output) {
        val before = TierESourceFingerprint.read(root, null)
        Files.writeString(output.resolve("source-state-before.json"), "$before\n")
        val native = TierENativeFixtureBuild.prepare(root, config.build.nativeBuildDir)
        val nativeLibrary = native.resolve("libmazewall_stack_agent.so")
        val identity = TierEMatrixProvenance.capture(root, endpoint, config.container.image, nativeLibrary)
        Files.writeString(
            output.resolve("run-manifest.json"),
            buildJsonObject {
            put("schema_version", 3)
            put("forks", forks)
            put("warmups", 3)
            put("measured_batches", 10)
            put("iterations_per_batch", iterations)
            put("workload_profile", workloadProfile)
            put("instrumented_fork_parity", parity)
            put("source_digest", before.getValue("source_digest"))
            for ((key, value) in identity.json()) put(key, value)
        }.toString() +
            "\n",
        )
        val uid = Files.getAttribute(root, "unix:uid") as Int
        val gid = Files.getAttribute(root, "unix:gid") as Int
        var failures = 0
        for ((fork, mode) in schedule) {
            val artifact = output.resolve("fork-$fork-$mode")
            Files.createDirectories(artifact)
            val status = if (mode in setOf("baseline", "user-notif-no-stack", "user-notif-stack")) {
                TierEDirectContainerArm.run(
                    endpoint,
                    config.container.image,
                    root,
                    artifact,
                    uid,
                    gid,
                    config.measurement.workloadCpu,
                    mode,
                    iterations,
                    workloadProfile,
                    config.deadlines.armSeconds,
                )
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
        check(identity.unchanged(root, endpoint, config.container.image, nativeLibrary)) { "Tier E matched binary identity changed" }
        val summary = TierESplitMatchedSummary.summarize(output, failures, drift)
        Files.writeString(output.resolve("summary.json"), "$summary\n")
        val success = (summary.getValue("terminal") as kotlinx.serialization.json.JsonObject).getValue("success") == JsonPrimitive(true)
        check(success) { "Matched matrix incomplete: $summary" }
        }
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
        val status = TierEArmAttempt.run(artifact) {
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
        }
        Files.writeString(artifact.resolve("instrumented.env"), "instrumented=${if (phases) 1 else 0}\n")
        return status
    }
}
