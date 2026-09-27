package io.mazewall.profiler.tierE.tools

import io.mazewall.profiler.tierE.tools.controller.TierESplitFixtureController
import io.mazewall.testing.containers.ContainerEndpointDiscovery
import io.mazewall.testing.containers.ContainerEngine
import io.mazewall.testing.containers.EndpointConfig
import io.mazewall.testing.containers.EndpointSelection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

public fun main(args: Array<String>) {
    if (args.size == 4 && args[0] == "phase-replay-dir") {
        TierERootfulPhaseEvidence.replay(Path.of(args[1]), Path.of(args[2]), Path.of(args[3])).getOrThrow()
        return
    }
    if (args.size == 4 && args[0] == "phase-replay") {
        require(Path.of(args[1]).toRealPath() != Path.of(args[3]).toAbsolutePath()) { "Replay output must differ from sample directory" }
        TierERootfulPhaseEvidence.verify(Path.of(args[1]), Path.of(args[2])).getOrThrow()
        Files.writeString(Path.of(args[3]), "PASS policy_eligibility=false\n")
        return
    }
    if (args.size in 3..4 && args[0] == "rootful-phase") {
        val endpoint = when (
            val selected = ContainerEndpointDiscovery().discover(
            EndpointConfig(ContainerEngine.DOCKER, System.getenv("MAZEWALL_DOCKER_HOST") ?: "unix:///var/run/docker.sock"),
        )
        ) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Native phase probe requires rootful Docker: ${selected.reason}")
        }
        if (args.size == 4) {
            TierERootfulPhaseProbe.runWithNativePackage(Path.of(args[1]), Path.of(args[2]), endpoint, Path.of(args[3]))
        } else {
            TierERootfulPhaseProbe.run(Path.of(args[1]), Path.of(args[2]), endpoint)
        }
        return
    }
    if (args.size in 4..5 && args[0] == "rootful-phase-profile") {
        TierEExperimentProfile.requireNoLegacyExperimentEnvironment()
        val profile = TierERootfulGateProfile.load(Path.of(args[3]))
        val endpoint = when (
            val selected = ContainerEndpointDiscovery().discover(
            EndpointConfig(ContainerEngine.DOCKER, TierEProfileEndpoint.select()),
        )
        ) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Profile-selected phase requires local rootful Docker: ${selected.reason}")
        }
        TierERootfulPhaseProbe.runProfile(Path.of(args[1]), Path.of(args[2]), endpoint, profile, args.getOrNull(4)?.let(Path::of))
        return
    }
    if (args.size == 3 && args[0] == "experiment-config") {
        TierEExperimentProfile.requireNoLegacyExperimentEnvironment()
        val profile = TierEExperimentProfile.load(Path.of(args[1]))
        Files.writeString(Path.of(args[2]), "${profile.resolvedJson()}\n")
        return
    }
    if (args.size in 4..5 && args[0] in setOf("experiment-run", "node-noise-profile", "capacity-profile")) {
        TierEExperimentProfile.requireNoLegacyExperimentEnvironment()
        val profile = TierEExperimentProfile.load(Path.of(args[1]))
        val requestedKind = when (args[0]) {
            "experiment-run" -> "trustworthy"
            "capacity-profile" -> "capacity"
            else -> "node-noise"
        }
        require(profile.kind == requestedKind) { "Profile kind must be $requestedKind" }
        val root = Path.of(args[2])
        val output = Path.of(args[3])
        val endpoint = when (
            val selected = ContainerEndpointDiscovery().discover(
            EndpointConfig(ContainerEngine.DOCKER, TierEProfileEndpoint.select()),
        )
        ) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Tier E experiment requires rootful Docker: ${selected.reason}")
        }
        if (args.size == 5) {
            TierEProfileExperimentRunner.runWithNativePackage(profile, root, output, endpoint, Path.of(args[4]))
        } else {
            TierEProfileExperimentRunner.run(profile, root, output, endpoint)
        }
        return
    }
    if (args.size == 3 && args[0] == "perf-evidence") {
        require(args[2] in setOf("true", "false")) { "JIT map requirement must be boolean" }
        TierETrustworthyPerfEvidence.write(Path.of(args[1]), args[2].toBoolean())
        return
    }
    if (args.size == 2 && args[0] == "allocation-evidence") {
        TierETrustworthyAllocationEvidence.write(Path.of(args[1]))
        return
    }
    if (args.size == 4 && args[0] == "tier-e-checks") {
        TierEChecksRunner.run(args[1], Path.of(args[2]), Path.of(args[3]))
        return
    }
    if (args.size == 4 && args[0] == "ring-microbenchmark") {
        TierERingMicrobenchmarkRunner.run(args[1], Path.of(args[2]), Path.of(args[3]))
        return
    }
    if (args.size == 3 && args[0] == "native-transports") {
        TierENativeScenarioRunner.compareTransports(Path.of(args[1]), Path.of(args[2]))
        return
    }
    if (args.size == 4 && args[0] == "native-phase-gate") {
        TierENativeScenarioRunner.checkPhaseGate(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]))
        return
    }
    if (args.size == 4 && args[0] == "native-comparable-gate") {
        TierENativeScenarioRunner.checkComparablePhaseGate(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]))
        return
    }
    if (args.size == 4 && args[0] == "native-comparable-evidence") {
        val result = TierEBenchmarkPhaseGate.comparable(
            args[1],
            Files.readString(Path.of(args[2])),
            TierEBenchmarkPhaseGate.baselines(Path.of(args[3]), args[1]),
        )
        println(
            buildJsonObject {
            for ((key, value) in result) put(key, value)
            put("policy_eligibility", false)
        },
        )
        return
    }
    if (args.size == 5 && args[0] == "native-scenarios") {
        TierENativeScenarioRunner.run(args[1], Path.of(args[2]), Path.of(args[3]), Path.of(args[4]))
        return
    }
    if (args.size == 3 && args[0] == "node-noise-run") {
        val endpoint = when (
            val selected = ContainerEndpointDiscovery().discover(
                EndpointConfig(
                    ContainerEngine.DOCKER,
            System.getenv("MAZEWALL_DOCKER_HOST") ?: "unix:///var/run/docker.sock",
                ),
            )
        ) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Tier E node-noise probe requires rootful Docker: ${selected.reason}")
        }

        fun positive(
            name: String,
            default: Int,
        ): Int =
            (System.getenv(name) ?: default.toString())
                .toIntOrNull()
            ?.takeIf { it > 0 } ?: throw IllegalArgumentException("Invalid $name")
        TierENodeNoiseProbe.run(
            Path.of(args[1]),
            Path.of(args[2]),
            endpoint,
            System.getenv("MAZEWALL_TIER_E_DOCKER_IMAGE") ?: "mazewall-tier-e-rootful:perf-20260919-external-profiler",
            positive("MAZEWALL_TIER_E_NODE_NOISE_RUNS", 5),
            positive("MAZEWALL_TIER_E_NODE_NOISE_DURATION_SECONDS", 3),
            positive("MAZEWALL_TIER_E_NODE_NOISE_CPU", 2),
        )
        return
    }
    if (args.size == 4 && args[0] == "coverage-run") {
        val endpoint = when (
            val selected = ContainerEndpointDiscovery().discover(
                EndpointConfig(
                    ContainerEngine.DOCKER,
            System.getenv("MAZEWALL_DOCKER_HOST") ?: "unix:///var/run/docker.sock",
                ),
            )
        ) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Tier E coverage run requires rootful Docker: ${selected.reason}")
        }
        TierECoverageExperiment.run(
            args[1],
            Path.of(args[2]),
            Path.of(args[3]),
            endpoint,
            System.getenv("MAZEWALL_TIER_E_DOCKER_IMAGE") ?: "mazewall-tier-e-rootful:perf-20260919-external-profiler",
        )
        return
    }
    if (args.size == 3 && args[0] == "capacity-run") {
        val root = Path.of(args[1])
        val config = TierESustainedCapacitySettings.load()
        val endpoint = when (
            val selected = ContainerEndpointDiscovery().discover(
                EndpointConfig(
                    ContainerEngine.DOCKER,
            System.getenv("MAZEWALL_DOCKER_HOST") ?: "unix:///var/run/docker.sock",
                ),
            )
        ) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Tier E capacity requires rootful Docker: ${selected.reason}")
        }
        val image = System.getenv("MAZEWALL_TIER_E_DOCKER_IMAGE") ?: "mazewall-tier-e-rootful:perf-20260919-external-profiler"
        TierESustainedCapacityRunner.run(config, Path.of(args[2]), root, nodeNoise = { destination ->
            fun positive(
                name: String,
                default: Int,
            ): Int =
                (System.getenv(name) ?: default.toString())
                    .toIntOrNull()
                ?.takeIf { it > 0 } ?: throw IllegalArgumentException("Invalid $name")
            TierENodeNoiseProbe.run(
                root,
                destination,
                endpoint,
                image,
                positive("MAZEWALL_TIER_E_NODE_NOISE_RUNS", 5),
                positive("MAZEWALL_TIER_E_NODE_NOISE_DURATION_SECONDS", 3),
                positive("MAZEWALL_TIER_E_NODE_NOISE_CPU", 2),
            )
        }) { stage, artifact, shadow ->
            val maxRecords = "-Dmazewall.tier-e.benchmark-max-retained-records=${stage.maxRetainedRecords}"
            val shadowOption = if (shadow) " -Dmazewall.tier-e.benchmark-strace-output=/artifacts/strace-shadow.log" else ""
            val env = System.getenv() +
                mapOf(
                "MAZEWALL_TIER_E_BENCHMARK_SCOPE" to "tier-e-sustained-collector",
                "MAZEWALL_TIER_E_SUSTAINED_RATE_PER_SECOND" to stage.rate.toString(),
                "MAZEWALL_TIER_E_SUSTAINED_LOAD" to config.load,
                "MAZEWALL_TIER_E_COLLECTOR_RECEIPT_CLOCK" to config.receiptClock,
                "MAZEWALL_TIER_E_CALIBRATION" to "false",
                "MAZEWALL_TIER_E_ITERATIONS" to config.iterations.toString(),
                "MAZEWALL_TIER_E_WARMUPS" to config.warmups.toString(),
                "MAZEWALL_TIER_E_BATCHES" to stage.batches.toString(),
                "MAZEWALL_TIER_E_FORKS" to stage.forks.toString(),
                "MAZEWALL_TIER_E_RUN_LEVEL" to "custom",
                "MAZEWALL_TIER_E_BENCHMARK_JAVA_OPTIONS" to "${System.getenv("MAZEWALL_TIER_E_BENCHMARK_JAVA_OPTIONS").orEmpty()} $maxRecords$shadowOption",
            )
            TierETrustworthyRunner.run(
                TierETrustworthySettings.load(env),
                endpoint,
                root,
                artifact,
                image,
                System.getenv("MAZEWALL_TIER_E_BENCHMARK_CPUS"),
            )
        }
        return
    }
    if (args.size == 3 && args[0] == "rootful-gate") {
        val engine = ContainerEngine.valueOf((System.getenv("TIER_E_CONTAINER_ENGINE") ?: "docker").uppercase())
        val uri = if (engine == ContainerEngine.PODMAN) {
            System.getenv("MAZEWALL_CONTAINER_HOST")
        } else {
            System.getenv("MAZEWALL_DOCKER_HOST") ?: "unix:///var/run/docker.sock"
        }
        val endpoint = when (val selected = ContainerEndpointDiscovery().discover(EndpointConfig(engine, uri))) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Tier E rootful gate requires local rootful engine: ${selected.reason}")
        }
        TierERootfulGateRunner.run(
            Path.of(args[1]),
            Path.of(args[2]),
            endpoint,
            System.getenv("TIER_E_DOCKER_IMAGE") ?: "mazewall-tier-e-rootful:latest",
        )
        return
    }
    if (args.size in 4..6 && args[0] == "rootful-gate-profile") {
        TierEExperimentProfile.requireNoLegacyExperimentEnvironment()
        val profile = TierERootfulGateProfile.load(Path.of(args[3]))
        val endpoint = when (
            val selected = ContainerEndpointDiscovery().discover(
            EndpointConfig(ContainerEngine.DOCKER, TierEProfileEndpoint.select()),
        )
        ) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Rootful gate requires local Docker: ${selected.reason}")
        }
        val phase = args.getOrNull(4)?.takeUnless { it == "--no-phase" }?.let(Path::of)
        if (args.size == 6) {
            TierERootfulGateRunner.runWithNativePackage(
                Path.of(args[1]),
                Path.of(args[2]),
                endpoint,
                profile.image,
                profile.cpuSet,
                phase,
                Path.of(args[5]),
            )
        } else {
            TierERootfulGateRunner.run(Path.of(args[1]), Path.of(args[2]), endpoint, profile.image, profile.cpuSet, phase)
        }
        return
    }
    if (args.size == 2 && args[0] == "trustworthy-source-state") {
        println(TierETrustworthySourceState.read(Path.of(args[1])))
        return
    }
    if (args.size == 3 && args[0] == "trustworthy-run") {
        val endpoint = when (
            val selected = ContainerEndpointDiscovery().discover(
                EndpointConfig(ContainerEngine.DOCKER, System.getenv("MAZEWALL_DOCKER_HOST") ?: "unix:///var/run/docker.sock"),
            )
        ) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Tier E trustworthy run requires rootful Docker: ${selected.reason}")
        }
        TierETrustworthyRunner.run(
            TierETrustworthySettings.load(),
            endpoint,
            Path.of(args[1]),
            Path.of(args[2]),
            System.getenv("MAZEWALL_TIER_E_DOCKER_IMAGE") ?: "mazewall-tier-e-rootful:perf-20260919-external-profiler",
            System.getenv("MAZEWALL_TIER_E_BENCHMARK_CPUS"),
        )
        return
    }
    if (args.size == 3 && args[0] == "bundle-fingerprint") {
        require(args[2] in setOf("true", "false")) { "USDT must be true or false" }
        println(
            TierEBenchmarkBundleFingerprint.read(
                Path.of(args[1]),
                args[2] == "true",
            System.getenv("MAZEWALL_TIER_E_NATIVE_BUILD_TYPE") ?: "Release",
            ),
        )
        return
    }
    if (args.size in setOf(2, 3) && args[0] == "bundle-verify") {
        TierEBenchmarkBundleManifest.verify(Path.of(args[1]), args.getOrNull(2))
        return
    }
    if (args.size in setOf(2, 3) && args[0] == "bundle-write-manifest") {
        TierEBenchmarkBundleManifest.write(Path.of(args[1]), args.getOrNull(2) ?: "")
        return
    }
    if (args.size in 5..6 && args[0] == "bundle-build") {
        require(args[3] in setOf("true", "false") && args[4] in setOf("true", "false")) {
            "Bundle build expects USDT and rebuild boolean flags"
        }
        val repository = Path.of(args[1])
        val nativeType = System.getenv("MAZEWALL_TIER_E_NATIVE_BUILD_TYPE") ?: "Release"
        val fingerprintStarted = System.nanoTime()
        val fingerprint = TierEBenchmarkBundleFingerprint.read(repository, args[3].toBoolean(), nativeType)
        val fingerprintMillis = java.util.concurrent.TimeUnit.NANOSECONDS
            .toMillis(System.nanoTime() - fingerprintStarted)
        val cache = System.getenv("MAZEWALL_TIER_E_BUILD_CACHE_ROOT")?.let(Path::of)
            ?: repository.resolve("build/tier-e-benchmark-cache")
        val status = if (args.size == 5) {
            TierEBenchmarkBundleBuilder.build(
                repository,
                Path.of(args[2]),
                cache,
                args[3].toBoolean(),
                args[4].toBoolean(),
                nativeType,
                fingerprint,
                fingerprintMillis,
            )
        } else {
            fun tool(vararg command: String): String {
                val result = io.mazewall.testing.containers
                    .runContainerCommand(command.toList())
                check(result.exitCode == 0) { "Bundle native toolchain unavailable: ${command.first()} (${result.exitCode})" }
                return result.output.trim()
            }
            val imageId = requireNotNull(System.getenv("MAZEWALL_TIER_E_NATIVE_PACKAGE_IMAGE_ID")) {
                "Native package image identity is required"
            }
            val architecture = requireNotNull(System.getenv("MAZEWALL_TIER_E_NATIVE_PACKAGE_ARCH")) {
                "Native package architecture is required"
            }
            val expected = TierENativeArtifactPackage.Expected(
                imageId,
                nativeType,
                args[3].toBoolean(),
                tool("c++", "--version").lineSequence().first(),
                tool("java", "-version"),
                tool("cmake", "--version").lineSequence().first(),
                architecture,
            )
            TierEBenchmarkBundleBuilder.buildPrepared(
                repository,
                Path.of(args[2]),
                cache,
                args[3].toBoolean(),
                args[4].toBoolean(),
                nativeType,
                Path.of(args[5]),
                expected,
                fingerprint,
                fingerprintMillis,
            )
        }
        println("tier-e-benchmark-bundle: status=$status fingerprint=$fingerprint bundle=${args[2]}")
        return
    }
    if (args.size == 2 && args[0] == "coverage-summary") {
        val summary = TierECoverageReport.write(Path.of(args[1]))
        println(
            buildJsonObject {
            put("artifact", Path.of(args[1]).resolve("coverage-summary.json").toString())
            put("completed", summary.getValue("run_completed"))
            put(
                "variants",
                JsonArray(
                    summary.getValue("variants").jsonArray.map { variant ->
                buildJsonObject {
                    val item = variant.jsonObject
                    put("variant", item.getValue("variant"))
                    put("all_forks_recover_all_paths", item.getValue("all_forks_recover_all_paths"))
                    put("cumulative_recall", item.getValue("cumulative_recall"))
                }
            },
                ),
            )
        },
        )
        return
    }
    if (args.size == 1 && args[0] == "version") {
        println("tier-e-tools-1")
        return
    }
    if (args.size == 3 && args[0] == "json-field") {
        val document = Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonObject
        val value = document[args[2]]?.jsonPrimitive?.takeIf { it.isString && it.content.isNotBlank() } ?: exitProcess(1)
        println(value.content)
        return
    }
    if (args.size == 3 && args[0] == "fixture-verdict") {
        val directory = Path.of(args[2])
        Files.writeString(directory.resolve("verdict.env"), TierEFixtureVerdict.validate(directory, args[1]))
        return
    }
    if (args.size == 3 && args[0] == "fixture-envelope") {
        Files.write(Path.of(args[2]), TierEFixtureEnvelope.create(args[1]))
        return
    }
    if (args.size == 5 && args[0] == "verify-operations") {
        val result = TierESplitOperationVerifier.verify(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]))
        Files.writeString(Path.of(args[4]), result.toString() + "\n")
        println(result)
        if (result.getValue("operation_coverage_verdict").jsonPrimitive.content != "PASS" ||
            result.getValue("terminal_evidence_integrity").jsonPrimitive.content != "COMPLETE"
        ) {
                exitProcess(1)
            }
        return
    }
    if (args.size == 8 && args[0] == "short-fork-row") {
        val row = TierESplitForkRow.parse(
            args[1].toInt(),
            args[2],
            args[3].toInt(),
            Path.of(args[4]),
            args[6].toInt(),
            args[7].takeIf { it != "null" }?.toInt(),
        )
        Files.writeString(Path.of(args[5]), row.toString() + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        if (row.getValue("fork_complete").toString() != "true") exitProcess(1)
        return
    }
    if (args.size == 6 && args[0] == "fault-fork-row") {
        val row = TierEHybridFaultRows.row(Path.of(args[1]), args[2].toInt(), args[3], args[4].toInt())
        Files.writeString(Path.of(args[5]), row.toString() + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        if (row.getValue("complete").toString() != "true") exitProcess(1)
        return
    }
    if (args.size == 4 && args[0] == "fault-matrix-summary") {
        val directory = Path.of(args[1])
        val summary = TierEHybridFaultRows.summary(directory, args[2].toInt(), args[3].toInt())
        Files.writeString(directory.resolve("summary.json"), summary.toString() + "\n")
        println(summary)
        if (summary.getValue("all_singletons_recovered").toString() != "true" ||
            summary.getValue("discovery_p99_under_500ms").toString() != "true" ||
            summary.getValue("source_state_unchanged").toString() != "true"
        ) {
                exitProcess(1)
            }
        return
    }
    if (args.size == 9 && args[0] == "live-fork-row") {
        require(args[5] in listOf("0", "1")) { "live flag must be 0 or 1" }
        val row = TierEHybridLiveRows.row(Path.of(args[1]), args[2].toInt(), args[3], args[4], args[5] == "1", args[6].toInt(), args[8].toInt())
        Files.writeString(Path.of(args[7]), row.toString() + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        if (row.getValue("fork_complete").toString() != "true") exitProcess(1)
        return
    }
    if (args.size == 4 && args[0] == "live-matrix-summary") {
        val dir = Path.of(args[1])
        val failed = args[2].toInt()
        val drift = args[3].toInt()
        val summary = TierEHybridLiveRows.summary(dir, failed, drift)
        Files.writeString(dir.resolve("summary.json"), summary.toString() + "\n")
        println(summary)
        if (failed != 0 || summary.getValue("completed").jsonPrimitive.content == "0" || drift != 0) exitProcess(1)
        return
    }
    if (args.size == 4 && args[0] == "short-matched-summary") {
        val dir = Path.of(args[1])
        val summary = TierESplitMatchedSummary.summarize(dir, args[2].toInt(), args[3].toInt())
        Files.writeString(dir.resolve("summary.json"), summary.toString() + "\n")
        println(summary)
        if ((summary.getValue("terminal") as kotlinx.serialization.json.JsonObject).getValue("success").toString() != "true") exitProcess(1)
        return
    }
    if (args.size == 7 && args[0] == "pid-reuse-probe") {
        val result = TierERealPidReuseProbe.run(
            args[1].toLong(),
            args[2].toLong(),
            args[3].toInt(),
            java.time.Duration.ofMillis((args[4].toDouble() * 1000).toLong()),
            java.time.Duration.ofMillis((args[5].toDouble() * 1000).toLong()),
            Path.of(args[6]),
        )
        println(result)
        return
    }
    if (args.size == 3 && args[0] == "pid-reuse-check") {
        val report = Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonObject
        val replacement = report["replacement"]?.jsonObject ?: exitProcess(1)
        if (report["host_pid_reuse_verdict"]?.jsonPrimitive?.content != "OBSERVED" ||
            replacement["starttime"]?.jsonPrimitive?.content != args[2]
        ) {
                exitProcess(1)
            }
        return
    }
    if (args.size == 3 && args[0] == "config") {
        val config = TierERunConfiguration.load(Path.of(args[1]))
        Files.writeString(Path.of(args[2]), config.resolvedJson() + "\n")
        return
    }
    if (args.size == 4 && args[0] == "fixture-run") {
        val configuration = TierERunConfiguration.load(Path.of(args[1]))
        val endpoint = when (val selected = ContainerEndpointDiscovery().discover(EndpointConfig(ContainerEngine.valueOf(configuration.container.engine.uppercase()), configuration.container.uri))) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Tier E fixture requires a rootful endpoint: ${selected.reason}")
        }
        val output = Path.of(args[3])
        Files.createDirectories(output)
        TierESplitFixtureController.run(
            configuration,
            endpoint,
            Path.of(args[2]),
            output,
            configuration.build.nativeBuildDir,
            configuration.measurement.seedStart,
        )
        println("Tier E Kotlin fixture artifacts: $output")
        return
    }
    if (args.size == 3 && args[0] == "fixture-run-env") {
        val launch = TierELegacyFixtureLaunch.translate(System.getenv())
        val endpoint = when (
            val selected = ContainerEndpointDiscovery().discover(
                EndpointConfig(
                    ContainerEngine.valueOf(
                        launch.configuration.container.engine
                    .uppercase(),
                    ),
                        launch.configuration.container.uri,
                ),
            )
        ) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Tier E fixture requires a rootful endpoint: ${selected.reason}")
        }
        val output = Path.of(args[2])
        Files.createDirectories(output)
        val ownedRun = System.getenv("TIER_E_SPLIT_RUN_ID")?.takeIf { System.getenv("TIER_E_SPLIT_CREATE_LOCK") != null }
        TierESplitFixtureController.run(
            launch.configuration,
            endpoint,
            Path.of(args[1]),
            output,
            launch.configuration.build.nativeBuildDir,
            launch.seed,
            launch.pinCpus,
            launch.benchmarkPhases,
            launch.workloadProfile,
            ownedRunId = ownedRun,
            ownedShared = System.getenv("TIER_E_SPLIT_SHARED_DIR")?.let(Path::of),
            creationLock = System.getenv("TIER_E_SPLIT_CREATE_LOCK")?.let(Path::of),
            creationState = System.getenv("TIER_E_SPLIT_CREATE_STATE")?.let(Path::of),
        )
        println("Tier E Kotlin fixture artifacts: $output")
        return
    }
    if (args.size == 9 && args[0] == "matched-matrix") {
        val configuration = TierERunConfiguration.load(
            Path.of(args[1]),
            overrides = mapOf("measurement.workload_cpu" to args[7], "measurement.collector_cpu" to args[8]),
        )
        val endpoint = when (
            val selected = ContainerEndpointDiscovery().discover(
                EndpointConfig(ContainerEngine.valueOf(configuration.container.engine.uppercase()), configuration.container.uri),
            )
        ) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Tier E matched matrix requires a rootful endpoint: ${selected.reason}")
        }
        TierEMatchedMatrix.run(
            configuration,
            endpoint,
            Path.of(args[2]),
            Path.of(args[3]),
            args[4].toInt(),
            args[5].toInt(),
            args[6].split(",").filter(String::isNotBlank),
            System.getenv("TIER_E_SPLIT_INSTRUMENTED_FORK_PARITY") ?: "even",
            System.getenv("TIER_E_SPLIT_WORKLOAD_PROFILE") ?: "syscall_dense",
        )
        return
    }
    if (args.size in setOf(6, 7) && args[0] == "live-matrix") {
        val configuration = TierERunConfiguration.load(Path.of(args[1]))
        val endpoint = when (
            val selected = ContainerEndpointDiscovery().discover(
                EndpointConfig(ContainerEngine.valueOf(configuration.container.engine.uppercase()), configuration.container.uri),
            )
        ) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Tier E live matrix requires a rootful endpoint: ${selected.reason}")
        }
        val selectedProfiles = if (args.size == 7) args[6].split(",").filter(String::isNotBlank) else TierELiveMatrix.profiles
        TierELiveMatrix.run(configuration, endpoint, Path.of(args[2]), Path.of(args[3]), args[4].toInt(), args[5].toInt(), selectedProfiles)
        return
    }
    if (args.size == 7 && args[0] == "fault-matrix") {
        val configuration = TierERunConfiguration.load(Path.of(args[1]))
        val endpoint = when (
            val selected = ContainerEndpointDiscovery().discover(
                EndpointConfig(ContainerEngine.valueOf(configuration.container.engine.uppercase()), configuration.container.uri),
            )
        ) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Tier E fault matrix requires a rootful endpoint: ${selected.reason}")
        }
        TierEFaultMatrix.run(
            configuration,
            endpoint,
            Path.of(args[2]),
            Path.of(args[3]),
            args[4].toInt(),
            args[5].toInt(),
            args[6],
        )
        return
    }
    if (args.size == 4 && args[0] == "negative-matrix") {
        val configuration = TierERunConfiguration.load(Path.of(args[1]))
        val endpoint = when (
            val selected = ContainerEndpointDiscovery().discover(
                EndpointConfig(ContainerEngine.valueOf(configuration.container.engine.uppercase()), configuration.container.uri),
            )
        ) {
            is EndpointSelection.Ready -> selected.endpoint
            is EndpointSelection.Skipped -> error("Tier E negative matrix requires a rootful endpoint: ${selected.reason}")
        }
        TierENegativeMatrix.run(configuration, endpoint, Path.of(args[2]), Path.of(args[3]))
        return
    }
    if (args.isNotEmpty() && args[0] == "coverage-matrix" && args.size in setOf(6, 8, 9)) {
        require(args[5] in setOf("0", "1")) { "Long matrix flag must be 0 or 1" }
        val hybridOnly = if (args.size == 9) args[8] else "0"
        require(hybridOnly in setOf("0", "1")) { "Hybrid-only flag must be 0 or 1" }
        val changes = if (args.size >= 8) mapOf("measurement.forks" to args[6], "fixture.operations" to args[7]) else emptyMap()
        val config = TierERunConfiguration.load(Path.of(args[1]), overrides = changes)
        TierECoverageMatrixRunner.run(config, Path.of(args[2]), Path.of(args[3]), args[5] == "1", args[4], hybridOnly == "1")
        return
    }
    if (args.size == 3 && args[0] == "arm-status") {
        if (args[2] !in setOf("0", "1")) exitProcess(2)
        val result = TierELongMatchedArmStatus.complete(Path.of(args[1]), args[2] == "1")
        if (!result) exitProcess(1)
        return
    }
    if (args.size == 3 && args[0] == "legacy-summary") {
        if (args[2] !in setOf("0", "1")) exitProcess(2)
        val result = TierELongMatchedArmStatus.legacySummary(Path.of(args[1]), args[2] == "1")
        if (!result) exitProcess(1)
        return
    }
    if (args.size == 5 && args[0] == "coverage") {
        val result = TierESplitCoverageVerifier.verify(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]))
        Files.writeString(Path.of(args[4]), Json.encodeToString(JsonElement.serializer(), result) + "\n")
        if (result.getValue("coverage_verdict").jsonPrimitive.content != "PASS") exitProcess(1)
        return
    }
    if (args.isNotEmpty() && args[0] == "target-cpu") {
        val directory = args.getOrNull(1)?.takeUnless { it.startsWith("--") } ?: exitProcess(2)

        fun option(name: String): Long? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toLongOrNull() }
        for (name in listOf("--host-pid", "--starttime")) {
            if (name in args && option(name) == null) exitProcess(2)
        }
        val result = TierETargetCpuSample.validate(Path.of(directory), option("--host-pid"), option("--starttime"))
        if (!result.valid) {
            System.err.println(result.reason)
            exitProcess(1)
        }
        println(
            buildJsonObject {
            put("reason", result.reason)
            for (field in listOf("pid", "host_pid", "starttime", "sample_boundary", "unit", "target_cpu_ns")) {
                put(field, result.sample!!.getValue(field))
            }
        },
        )
        return
    }
    if (args.isNotEmpty() && args[0] == "collector-cpu") {
        fun option(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val pid = option("--pid")?.toLongOrNull() ?: exitProcess(2)
        val hz = option("--hz")?.toLongOrNull() ?: exitProcess(2)
        val output = option("--out") ?: exitProcess(2)
        val boundary = option("--boundary") ?: exitProcess(2)
        val result = TierECollectorCpuSample.sample(pid, hz, Path.of(option("--proc-root") ?: "/proc"), Path.of(output), boundary)
        if (!result.valid) {
            System.err.println(result.reason)
            exitProcess(1)
        }
        println(result.sample)
        return
    }
    if (args.size == 3 && args[0] == "row") {
        val meta = Json.parseToJsonElement(args[1]).jsonObject
        println(Json.encodeToString(JsonElement.serializer(), TierELongMatchedRowBuilder.build(meta, Path.of(args[2]))))
        return
    }
    if (args.isNotEmpty() &&
        args[0] == "fingerprint" &&
        (args.size == 2 || (args.size == 4 && args[2] == "--verify"))
    ) {
        val previous = if (args.size == 4) Json.parseToJsonElement(Files.readString(Path.of(args[3]))).jsonObject else null
        val fingerprint = TierESourceFingerprint.read(Path.of(args[1]), previous)
        println(Json.encodeToString(JsonElement.serializer(), fingerprint))
        if (previous != null && fingerprint.getValue("unchanged").jsonPrimitive.content != "true") exitProcess(1)
        return
    }
    if (args.size == 3 && args[0] == "summarize") {
        val decision = TierELongMatchedEstimator.summarize(Path.of(args[1]))
        Files.writeString(
            Path.of(args[2]),
            Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }.encodeToString(JsonElement.serializer(), decision) +
            "\n",
        )
        println(
            buildJsonObject {
            put("metrics_status", decision.getValue("metrics_status"))
            put("incomplete", decision.getValue("incomplete"))
        },
        )
        if (decision.getValue("metrics_status").toString() != "\"COMPLETE\"") exitProcess(1)
        return
    }
    val result = when {
        args.size == 2 && args[0] == "verify-dir" -> TierELongMatchedDirectoryVerifier.verify(Path.of(args[1]))
        args.size == 6 && args[0] == "verify-jsonl" -> TierELongMatchedAcceptance.verifyJsonl(
            Path.of(args[1]),
            Path.of(args[2]),
            Path.of(args[3]),
            Path.of(args[4]),
            Path.of(args[5]),
        )
        else -> {
            System.err.println(
                "usage: json-field <file> <name> | arm-status <dir> <baseline:0|1> | legacy-summary <dir> <allow:0|1> | coverage <oracle> <manifest> <terminal> <verdict> | target-cpu <dir> [--host-pid N --starttime N] | collector-cpu --pid N --hz N --boundary after_terminal_report_before_shutdown --out FILE | row <meta-json> <artifact-dir> | verify-dir <decision-dir> | verify-jsonl <rows> <manifest> <before> <after> <terminal> | summarize <rows> <decision> | fingerprint <repo> [--verify <before>]",
            )
            exitProcess(2)
        }
    }
    println(
        buildJsonObject {
        put("schema_version", 1)
        put("verdict", result.verdict)
        put("verification_mode", if (args[0] == "verify-dir") "full-directory" else "jsonl-only")
        put("rows", result.rows)
        put("baseline_rows", result.baselines)
        put("profiled_rows", result.profiled)
        put("errors", JsonArray(result.errors.map(::JsonPrimitive)))
        put("policy_eligibility", false)
    },
    )
    if (result.verdict != "PASS") exitProcess(1)
}
