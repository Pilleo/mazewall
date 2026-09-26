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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

public fun main(args: Array<String>) {
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
        )
        println("Tier E Kotlin fixture artifacts: $output")
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
