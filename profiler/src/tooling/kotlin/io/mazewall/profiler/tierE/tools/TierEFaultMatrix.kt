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

/** Ring-drop fault schedule. Live snapshots and phase instrumentation stay outside this controller. */
public object TierEFaultMatrix {
    public fun run(
        config: TierERunConfiguration,
        endpoint: ContainerEndpoint,
        root: Path,
        output: Path,
        forks: Int,
        operations: Int,
        backend: String,
    ) {
        require(forks > 0 && operations >= 72 && operations % 24 == 0) { "Invalid Tier E fault matrix size" }
        require(backend in setOf("uprobe", "usdt")) { "Unknown Tier E fault backend: $backend" }
        Files.createDirectories(output)
        require(Files.list(output).use { it.findAny().isEmpty }) { "Tier E fault matrix requires a fresh directory" }
        val before = TierESourceFingerprint.read(root, null)
        Files.writeString(output.resolve("source-state-before.json"), "$before\n")
        val native = TierENativeFixtureBuild.prepare(root, config.build.nativeBuildDir)
        var failures = 0
        for (fork in 0 until forks) {
            for (emission in listOf("full-stream", "unique-stack-syscall")) {
                val artifact = output.resolve("fork-$fork-$emission")
                Files.createDirectories(artifact)
                val arm = config.copy(
                    fixture = config.fixture.copy(coverage = true, operations = operations, emissionMode = emission, recoveryMode = "recover"),
                    scenario = config.scenario.copy(
                        markerVersion = "v2",
                        markerBackend = backend,
                        faultSyscall = 257,
                        negativeArtifact = null,
                        identityCase = null,
                        liveSnapshots = false,
                        baseline = false,
                        benchmark = false,
                    ),
                )
                var status = 0
                try {
                    TierESplitFixtureController.run(arm, endpoint, root, artifact, native, 23063 + fork)
                } catch (failure: Exception) {
                    status = 1
                    Files.writeString(artifact.resolve("runner-failure.txt"), failure.stackTraceToString())
                }
                val row = TierEHybridFaultRows.row(artifact, fork, emission, status)
                Files.writeString(output.resolve("fork-results.jsonl"), "$row\n", CREATE, APPEND)
                if (row.getValue("complete") != JsonPrimitive(true)) failures += 1
            }
        }
        val after = TierESourceFingerprint.read(root, before)
        Files.writeString(output.resolve("source-state-after.json"), "$after\n")
        val sourceChanged = if (after["unchanged"] == JsonPrimitive(true)) 0 else 1
        val summary = TierEHybridFaultRows.summary(output, failures, sourceChanged)
        Files.writeString(output.resolve("summary.json"), "$summary\n")
        Files.writeString(
            output.resolve("run-manifest.json"),
            buildJsonObject {
                put("schema_version", 2)
                put("forks", forks)
                put("operations", operations)
                put("backend", backend)
                put("fault_syscall", 257)
                put("native_live_snapshots", false)
                put("phase_instrumentation", false)
                put("policy_eligibility", false)
            }.toString() +
                "\n",
        )
        check(summary.getValue("all_singletons_recovered") == JsonPrimitive(true)) { "Fault matrix did not recover every singleton" }
        check(summary.getValue("discovery_p99_under_500ms") == JsonPrimitive(true)) { "Fault discovery p99 exceeded 500 ms" }
        check(summary.getValue("source_state_unchanged") == JsonPrimitive(true)) { "Fault matrix source state changed" }
    }
}
