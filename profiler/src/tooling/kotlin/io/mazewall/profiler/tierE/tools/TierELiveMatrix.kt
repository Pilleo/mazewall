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

/** Live-versus-terminal publication schedule. It does not promote hybrid recovery. */
public object TierELiveMatrix {
    public val profiles: List<String> = listOf("syscall_dense", "stack_diverse", "class_churn")

    public fun run(
        config: TierERunConfiguration,
        endpoint: ContainerEndpoint,
        root: Path,
        output: Path,
        forks: Int,
        iterations: Int,
        selectedProfiles: List<String> = profiles,
    ) {
        require(forks > 0 && iterations > 0) { "Invalid Tier E live matrix size" }
        require(selectedProfiles.isNotEmpty() && selectedProfiles.all { it in profiles }) { "Unknown Tier E live profile" }
        Files.createDirectories(output)
        require(Files.list(output).use { it.findAny().isEmpty }) { "Tier E live matrix requires a fresh directory" }
        val before = TierESourceFingerprint.read(root, null)
        Files.writeString(output.resolve("source-state-before.json"), "$before\n")
        val native = TierENativeFixtureBuild.prepare(root, config.build.nativeBuildDir)
        var failed = 0
        for (fork in 0 until forks) {
            for (profile in selectedProfiles) {
                for (emission in listOf("full-stream", "unique-stack-syscall")) {
                    val liveOrder = if (fork % 2 == 0) listOf(false, true) else listOf(true, false)
                    for (live in liveOrder) {
                        val mode = if (emission == "full-stream") "dictionary-full-stream" else "dictionary-unique-edge"
                        val artifact = output.resolve("fork-$fork-$profile-$mode-live-${if (live) 1 else 0}")
                        Files.createDirectories(artifact)
                        val arm = config.copy(
                            fixture = config.fixture.copy(coverage = false, emissionMode = emission, recoveryMode = "recover"),
                            measurement = config.measurement.copy(iterations = iterations),
                            scenario = config.scenario.copy(
                                markerVersion = "v2",
                                stackTransport = "dictionary",
                                liveSnapshots = live,
                                benchmark = true,
                                baseline = false,
                                faultSyscall = null,
                                negativeArtifact = null,
                                identityCase = null,
                            ),
                        )
                        var status = 0
                        try {
                            TierESplitFixtureController.run(
                                arm,
                                endpoint,
                                root,
                                artifact,
                                native,
                                23063 + fork,
                                benchmarkPhases = true,
                                workloadProfile = profile,
                            )
                        } catch (failure: Exception) {
                            val uniqueEdgeOracle = emission == "unique-stack-syscall" &&
                                failure.message.orEmpty().contains("Benchmark operation oracle incomplete")
                            status = if (uniqueEdgeOracle) 0 else 1
                            Files.writeString(artifact.resolve("runner-failure.txt"), failure.stackTraceToString())
                        }
                        val row = TierEHybridLiveRows.row(artifact, fork, mode, profile, live, status, iterations * 10)
                        Files.writeString(output.resolve("fork-results.jsonl"), "$row\n", CREATE, APPEND)
                        if (row.getValue("fork_complete") != JsonPrimitive(true)) failed += 1
                    }
                }
            }
        }
        val after = TierESourceFingerprint.read(root, before)
        Files.writeString(output.resolve("source-state-after.json"), "$after\n")
        val drift = if (after["unchanged"] == JsonPrimitive(true)) 0 else 1
        val summary = TierEHybridLiveRows.summary(output, failed, drift)
        Files.writeString(output.resolve("summary.json"), "$summary\n")
        Files.writeString(
            output.resolve("run-manifest.json"),
            buildJsonObject {
                put("schema_version", 1)
                put("forks", forks)
                put("iterations", iterations)
                put("policy_eligibility", false)
            }.toString() +
                "\n",
        )
        check(failed == 0 && drift == 0 && summary.getValue("completed") != JsonPrimitive(0)) { "Live matrix incomplete: $summary" }
    }
}
