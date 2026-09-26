package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Immutable, validated developer-only configuration; no environment reads below [load]. */
public data class TierERunConfiguration(
    val container: ContainerSettings,
    val fixture: FixtureSettings,
    val measurement: MeasurementSettings,
    val scenario: TierEExecutionProfile = TierEExecutionProfile.parse(null, fixture.recoveryMode),
    val build: TierEBuildProfile = TierEBuildProfile.parse(null),
    val deadlines: TierEDeadlines = TierEDeadlines.parse(null),
    val artifacts: TierEArtifactPolicy = TierEArtifactPolicy.parse(null),
) {
    public data class ContainerSettings(
        val engine: String,
        val uri: String?,
        val image: String,
    )

    public data class FixtureSettings(
        val coverage: Boolean,
        val operations: Int,
        val emissionMode: String,
        val recoveryMode: String,
    )

    public data class MeasurementSettings(
        val forks: Int,
        val workloadCpu: Int,
        val collectorCpu: Int,
        val seedStart: Int = 23063,
        val warmups: Int = 3,
        val batches: Int = 10,
        val iterations: Int = 100,
    )

    public fun resolvedJson(): String =
        buildJsonObject {
        put("schema_version", 2)
        put(
            "container",
            buildJsonObject {
            put("engine", container.engine)
            put("uri", container.uri?.let(::JsonPrimitive) ?: JsonNull)
            put("image", container.image)
        },
        )
        put(
            "fixture",
            buildJsonObject {
            put("coverage", fixture.coverage)
            put("operations", fixture.operations)
            put("emission_mode", fixture.emissionMode)
            put("recovery_mode", fixture.recoveryMode)
        },
        )
        put(
            "measurement",
            buildJsonObject {
            put("forks", measurement.forks)
            put("workload_cpu", measurement.workloadCpu)
            put("collector_cpu", measurement.collectorCpu)
            put("seed_start", measurement.seedStart)
            put("warmups", measurement.warmups)
            put("batches", measurement.batches)
            put("iterations", measurement.iterations)
        },
        )
        put("policy_eligibility", false)
        put(
            "scenario",
            buildJsonObject {
            put("marker_version", scenario.markerVersion)
            put("marker_backend", scenario.markerBackend)
            put("stack_transport", scenario.stackTransport)
            put("socket_access", scenario.socketAccess)
            put("fault_syscall", scenario.faultSyscall?.let(::JsonPrimitive) ?: JsonNull)
            put("negative_artifact", scenario.negativeArtifact?.let(::JsonPrimitive) ?: JsonNull)
            put("identity_case", scenario.identityCase?.let(::JsonPrimitive) ?: JsonNull)
            put("live_snapshots", scenario.liveSnapshots)
            put("baseline", scenario.baseline)
            put("benchmark", scenario.benchmark)
            put("allow_incomplete", scenario.allowIncomplete)
        },
        )
        put(
            "build",
            buildJsonObject {
            put("native_build_dir", build.nativeBuildDir?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("reuse", build.reuse)
        },
        )
        put(
            "deadlines",
            buildJsonObject {
            put("arm_seconds", deadlines.armSeconds)
            put("terminal_seconds", deadlines.terminalSeconds)
            put("stop_seconds", deadlines.stopSeconds)
        },
        )
        put(
            "artifacts",
            buildJsonObject {
            put("output_root", artifacts.outputRoot?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("fresh", artifacts.fresh)
            put("retain_on_failure", artifacts.retainOnFailure)
        },
        )
    }.toString()

    public fun validate(): TierERunConfiguration {
        require(container.engine in setOf("docker", "podman") && container.image.isNotBlank())
        require(fixture.operations >= 72 && fixture.operations % 24 == 0 && measurement.forks > 0)
        require(measurement.workloadCpu >= 0 && measurement.collectorCpu >= 0 && measurement.workloadCpu != measurement.collectorCpu)
        require(measurement.seedStart >= 0 && measurement.warmups >= 0 && measurement.batches > 0 && measurement.iterations > 0)
        require(scenario.markerVersion in setOf("v1", "v2") && scenario.markerBackend in setOf("uprobe", "usdt"))
        require(scenario.stackTransport in setOf("dictionary", "inline-per-invocation") && scenario.socketAccess in setOf("group", "owner-only"))
        require(fixture.recoveryMode == "disabled" || scenario.markerVersion == "v2") { "Coverage recovery requires marker v2" }
        require(scenario.faultSyscall == null || (scenario.faultSyscall > 0 && fixture.coverage && fixture.recoveryMode == "recover"))
        require(!scenario.baseline || (!scenario.benchmark && scenario.faultSyscall == null))
        require(scenario.negativeArtifact == null || scenario.negativeArtifact in setOf("missing", "malformed", "symlink", "unclosed", "session-mismatch"))
        require(scenario.identityCase == null || scenario.identityCase in setOf("stale", "simulated-reuse", "real-reuse"))
        require(deadlines.armSeconds > 0 && deadlines.terminalSeconds > 0 && deadlines.stopSeconds > 0)
        return this
    }

    public companion object {
        private val topFields = setOf("schema_version", "container", "fixture", "measurement")
        private val versionTwoFields = topFields + setOf("scenario", "build", "deadlines", "artifacts")
        private val containerFields = setOf("engine", "uri", "image")
        private val fixtureFields = setOf("coverage", "operations", "emission_mode", "recovery_mode")
        private val measurementFields = setOf("forks", "workload_cpu", "collector_cpu")
        private val measurementV2Fields = measurementFields + setOf("seed_start", "warmups", "batches", "iterations")
        private val overridesAllowed = setOf(
            "container.engine",
            "container.uri",
            "container.image",
            "fixture.coverage",
            "fixture.operations",
            "fixture.emission_mode",
            "fixture.recovery_mode",
            "measurement.forks",
            "measurement.workload_cpu",
            "measurement.collector_cpu",
            "measurement.seed_start",
            "measurement.warmups",
            "measurement.batches",
            "measurement.iterations",
        )

        public fun load(
            profile: Path,
            environment: Map<String, String> = System.getenv(),
            overrides: Map<String, String> = emptyMap(),
        ): TierERunConfiguration {
            val document = Json.parseToJsonElement(Files.readString(profile)) as? JsonObject
                ?: throw IllegalArgumentException("Tier E profile must be a JSON object: $profile")
            val version = document["schema_version"]?.toString()
            require(version in setOf("1", "2")) { "Unsupported Tier E profile version" }
            val required = if (version == "2") versionTwoFields else topFields
            require(document.keys == required) { "Unexpected or missing Tier E profile fields: ${document.keys - required}, ${required - document.keys}" }

            fun section(
                name: String,
                required: Set<String>,
            ): JsonObject {
                val obj = document[name] as? JsonObject ?: throw IllegalArgumentException("Missing Tier E $name configuration")
                require(obj.keys == required) { "Unexpected or missing Tier E $name fields: ${obj.keys - required}, ${required - obj.keys}" }
                return obj
            }
            val container = section("container", containerFields)
            val fixture = section("fixture", fixtureFields)
            val measurement = section("measurement", if (version == "2") measurementV2Fields else measurementFields)
            require(overrides.keys.all { it in overridesAllowed }) { "Unknown Tier E configuration override: ${overrides.keys - overridesAllowed}" }
            val inferredEngine = if (environment["CONTAINER_HOST"].isNullOrBlank() ||
                !environment["DOCKER_HOST"].isNullOrBlank() ||
                !environment["MAZEWALL_DOCKER_HOST"].isNullOrBlank()
            ) {
                null
            } else {
                "podman"
            }
            val requestedEngine = overrides["container.engine"] ?: environment["TIER_E_CONTAINER_ENGINE"] ?: inferredEngine
                ?: (container["engine"] as? JsonPrimitive)?.content
            val uriAliases = (
                if (requestedEngine?.lowercase() == "podman") {
                listOf("MAZEWALL_DOCKER_HOST", "CONTAINER_HOST")
            } else {
                listOf("MAZEWALL_DOCKER_HOST", "DOCKER_HOST")
            }
            ).mapNotNull { environment[it]?.takeIf(String::isNotBlank) }
                .distinct()
            require(uriAliases.size <= 1) { "Conflicting Tier E container endpoint aliases" }
            val legacy = mapOf(
                "container.engine" to (environment["TIER_E_CONTAINER_ENGINE"] ?: inferredEngine),
                "container.uri" to uriAliases.singleOrNull(),
                "container.image" to environment["TIER_E_DOCKER_IMAGE"],
                "fixture.operations" to environment["TIER_E_SPLIT_COVERAGE_OPERATIONS"],
                "fixture.emission_mode" to environment["TIER_E_SPLIT_EMISSION_MODE"],
                "fixture.recovery_mode" to environment["TIER_E_SPLIT_RECOVERY_MODE"],
                "measurement.forks" to environment["TIER_E_SPLIT_COVERAGE_FORKS"],
            ).filterValues { !it.isNullOrBlank() }

            fun value(
                section: String,
                key: String,
            ): String {
                val field = "$section.$key"
                val supplied = overrides[field] ?: legacy[field]
                if (supplied != null) return supplied
                val source = when (section) {
                    "container" -> container
                    "fixture" -> fixture
                    else -> measurement
                }
                return source[key]?.let { if (it is JsonPrimitive && it.isString) it.content else it.toString() }
                    ?: throw IllegalArgumentException("Missing Tier E configuration: $field")
            }
            val engine = value("container", "engine").lowercase()
            require(engine in setOf("docker", "podman")) { "Unknown Tier E engine: $engine" }
            val configuredUri = container["uri"]
            require(configuredUri == JsonNull || (configuredUri is JsonPrimitive && configuredUri.isString)) {
                "Tier E container URI must be a string or null"
            }
            val uri = overrides["container.uri"] ?: legacy["container.uri"] ?: (configuredUri as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content
            require(uri == null || uri.startsWith("unix://") || uri.startsWith("ssh://") || uri.startsWith("tcp://")) {
                "Invalid Tier E container URI"
            }
            val operations = value("fixture", "operations").toIntOrNull()
            require(operations != null && operations >= 72 && operations % 24 == 0) { "Tier E operations must be >=72 and divisible by 24" }
            val forks = value("measurement", "forks").toIntOrNull()
            require(forks != null && forks > 0) { "Tier E forks must be positive" }
            val emission = value("fixture", "emission_mode")
            val recovery = value("fixture", "recovery_mode")
            require(emission in setOf("full-stream", "unique-stack-syscall")) { "Unknown Tier E emission mode: $emission" }
            require(recovery in setOf("disabled", "shadow", "recover")) { "Unknown Tier E recovery mode: $recovery" }
            val coverage = value("fixture", "coverage")
            require(coverage in setOf("true", "false")) { "Tier E coverage must be boolean" }
            val workloadCpu = value("measurement", "workload_cpu").toIntOrNull()
            val collectorCpu = value("measurement", "collector_cpu").toIntOrNull()
            require(workloadCpu != null && collectorCpu != null && workloadCpu >= 0 && collectorCpu >= 0 && workloadCpu != collectorCpu) {
                "Tier E measured CPUs must be distinct nonnegative integers"
            }
            return TierERunConfiguration(
                ContainerSettings(engine, uri, value("container", "image").also { require(it.isNotBlank()) }),
                FixtureSettings(coverage.toBoolean(), operations, emission, recovery),
                MeasurementSettings(
                    forks,
                    workloadCpu,
                    collectorCpu,
                    if (version == "2") {
                        value("measurement", "seed_start").toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid Tier E seed")
                    } else {
                        23063
                    },
                    if (version == "2") {
                        value("measurement", "warmups").toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid Tier E warmups")
                    } else {
                        3
                    },
                    if (version == "2") {
                        value("measurement", "batches").toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid Tier E batches")
                    } else {
                        10
                    },
                    if (version == "2") {
                        value("measurement", "iterations").toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid Tier E iterations")
                    } else {
                        100
                    },
                ),
                TierEExecutionProfile.parse(document["scenario"] as? JsonObject, recovery),
                TierEBuildProfile.parse(document["build"] as? JsonObject),
                TierEDeadlines.parse(document["deadlines"] as? JsonObject),
                TierEArtifactPolicy.parse(document["artifacts"] as? JsonObject),
            ).validate()
        }
    }
}
