package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Versioned, fail-closed experiment inputs; only the engine endpoint is machine-local. */
public data class TierEExperimentProfile(
    val kind: String,
    val trustworthy: TierETrustworthySettings,
    val nodeNoise: NodeNoise,
    val image: String = "mazewall-tier-e-rootful:perf-20260919-external-profiler",
    val cpuSet: String? = null,
    val capacity: TierESustainedCapacitySettings? = null,
) {
    public data class NodeNoise(
        val runs: Int,
        val durationSeconds: Int,
        val cpu: Int,
    )

    public fun resolvedJson(): JsonObject =
        buildJsonObject {
        put("schema_version", if (cpuSet == null) 1 else 2)
        put("kind", kind)
        put("configuration", trustworthy.resolvedJson())
        put(
            "node_noise",
            buildJsonObject {
            put("runs", nodeNoise.runs)
            put("duration_seconds", nodeNoise.durationSeconds)
            put("cpu", nodeNoise.cpu)
        },
        )
        put("policy_eligibility", false)
        if (cpuSet != null) {
            put("image", image)
            put("cpu_set", cpuSet)
        }
        capacity?.let { schedule ->
            put(
                "capacity",
                buildJsonObject {
                put("pilot_rates", schedule.pilotRates.joinToString(" "))
                put("run_level", schedule.runLevel)
                put("load", schedule.load)
                put("receipt_clock", schedule.receiptClock)
                put("stage_seconds", schedule.stageSeconds)
                put("iterations", schedule.iterations)
                put("warmups", schedule.warmups)
                put("max_retained_records", schedule.maxRetainedRecordsOverride)
                put("records_per_operation_upper_bound", schedule.recordsPerOperationUpperBound)
                put("retained_budget_bytes", schedule.retainedBudgetBytes)
                put("run_decision", schedule.runDecision)
                put("decision_forks", schedule.decisionForks)
                put("decision_stage_seconds", schedule.decisionStageSeconds)
                put("shadow_on_failure", schedule.shadowOnFailure)
                put("run_node_noise_probe", schedule.runNodeNoiseProbe)
            },
            )
        }
    }

    public companion object {
        public fun requireNoLegacyExperimentEnvironment(environment: Map<String, String> = System.getenv()) {
            val conflicts = environment.keys.filter { name ->
                name.startsWith("MAZEWALL_TIER_E_") ||
                    name.startsWith("TIER_E_SUSTAINED_") ||
                    name in setOf("TIER_E_DOCKER_IMAGE", "TIER_E_CONTAINER_ENGINE")
            }
            require(conflicts.isEmpty()) {
                "Profile-selected Tier E run conflicts with legacy settings: ${conflicts.sorted()}; select an explicit legacy subcommand for historical replay"
            }
        }

        private val fields = mapOf(
            "run_level" to "RUN_LEVEL",
            "benchmark_scope" to "BENCHMARK_SCOPE",
            "benchmark_workload" to "BENCHMARK_WORKLOAD",
            "lifecycle_scenario" to "LIFECYCLE_SCENARIO",
            "forks" to "FORKS",
            "batches" to "BATCHES",
            "warmups" to "WARMUPS",
            "iterations" to "ITERATIONS",
            "decision_forks" to "DECISION_FORKS",
            "require_decision_forks" to "REQUIRE_DECISION_FORKS",
            "child_timeout_seconds" to "CHILD_TIMEOUT_SECONDS",
            "worker_timeout_seconds" to "USER_NOTIF_WORKER_TIMEOUT_SECONDS",
            "user_notif_child_timeout_seconds" to "USER_NOTIF_CHILD_TIMEOUT_SECONDS",
            "perf_record" to "PERF_RECORD",
            "allocation_record" to "ALLOCATION_RECORD",
            "perf_jit_map" to "PERF_JIT_MAP",
            "verify_build" to "VERIFY_BUILD",
            "calibration" to "CALIBRATION",
            "native_build_type" to "NATIVE_BUILD_TYPE",
            "sustained_rate_per_second" to "SUSTAINED_RATE_PER_SECOND",
            "sustained_load" to "SUSTAINED_LOAD",
            "canonical_cache" to "CANONICAL_CACHE",
            "capture_profile" to "CAPTURE_PROFILE",
            "sys_enter_attachment_backend" to "SYS_ENTER_ATTACHMENT_BACKEND",
            "kernel_coverage_mode" to "KERNEL_COVERAGE_MODE",
            "sys_enter_attachment_shape" to "SYS_ENTER_ATTACHMENT_SHAPE",
            "collector_receipt_clock" to "COLLECTOR_RECEIPT_CLOCK",
            "perf_call_graph" to "PERF_CALL_GRAPH",
            "benchmark_java_options" to "BENCHMARK_JAVA_OPTIONS",
            "fixture_child_termination_after_millis" to "FIXTURE_CHILD_TERMINATION_AFTER_MILLIS",
            "benchmark_raw_sys_enter_audit" to "BENCHMARK_RAW_SYS_ENTER_AUDIT",
            "benchmark_exact_admission_audit" to "BENCHMARK_EXACT_ADMISSION_AUDIT",
        )
        private val integerFields = setOf(
            "forks",
            "batches",
            "warmups",
            "iterations",
            "decision_forks",
            "child_timeout_seconds",
            "worker_timeout_seconds",
            "user_notif_child_timeout_seconds",
            "sustained_rate_per_second",
            "fixture_child_termination_after_millis",
        )
        private val booleanFields = setOf(
            "require_decision_forks",
            "perf_record",
            "allocation_record",
            "perf_jit_map",
            "verify_build",
            "calibration",
            "benchmark_raw_sys_enter_audit",
            "benchmark_exact_admission_audit",
        )

        public fun load(profile: Path): TierEExperimentProfile {
            require(Files.size(profile) <= 65_536L) { "Tier E profile exceeds size bound" }
            val text = Files.readString(profile)
            TierEProfileJson.requireUniqueKeys(text)
            val document = Json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("Experiment profile must be a JSON object")
            val schema = document["schema_version"] as? JsonPrimitive
            val version = schema?.takeUnless(JsonPrimitive::isString)?.intOrNull
            require(version in setOf(1, 2)) { "Unsupported experiment profile version" }
            val selectedKind = document.getValue("kind").jsonPrimitive
            require(selectedKind.isString) { "Experiment kind must be a JSON string" }
            val kind = selectedKind.content
            require(kind in setOf("trustworthy", "node-noise", "capacity") && (version == 2 || kind != "capacity")) {
                "Unknown experiment kind"
            }
            val required = setOf("schema_version", "kind", "settings", "node_noise") +
                (if (version == 2) setOf("container") else emptySet()) +
                (if (kind == "capacity") setOf("capacity") else emptySet())
            require(document.keys == required) { "Unknown or missing experiment profile fields: ${document.keys - required}, ${required - document.keys}" }
            val settings = document["settings"] as? JsonObject ?: throw IllegalArgumentException("Missing experiment settings")
            require(settings.keys.all { it in fields }) { "Unknown experiment setting: ${settings.keys - fields.keys}" }
            val values = settings
                .map { (field, item) ->
                require(item is JsonPrimitive) { "Non-scalar experiment setting: $field" }
                val valid = when (field) {
                    in integerFields -> !item.isString && item.intOrNull != null
                    in booleanFields -> !item.isString && item.booleanOrNull != null
                    else -> item.isString
                }
                require(valid) { "Experiment setting has wrong JSON type: $field" }
                "MAZEWALL_TIER_E_${fields.getValue(field)}" to item.content
            }.toMap()
            val noise = document["node_noise"] as? JsonObject ?: throw IllegalArgumentException("Missing node-noise settings")
            require(noise.keys == setOf("runs", "duration_seconds", "cpu")) { "Unknown node-noise setting" }

            fun number(field: String): Int =
                noise
                    .getValue(field)
                    .jsonPrimitive
                    .takeUnless(JsonPrimitive::isString)
                    ?.intOrNull
                ?: throw IllegalArgumentException("Invalid node-noise $field")
            val nodeNoise = NodeNoise(number("runs"), number("duration_seconds"), number("cpu"))
            require(nodeNoise.runs in 1..20 && nodeNoise.durationSeconds in 1..30 && nodeNoise.cpu >= 0) {
                "Invalid node-noise limits"
            }
            // A selected profile never inherits legacy experiment knobs. The endpoint remains
            // an explicit caller-supplied machine setting rather than a benchmark input.
            val container = if (version == 2) {
                document["container"] as? JsonObject
                ?: throw IllegalArgumentException("Missing experiment container")
            } else {
                null
            }
            require(container == null || container.keys == setOf("image", "workload_cpu", "collector_cpu")) {
                "Unknown or missing experiment container fields"
            }
            val image = if (container == null) {
                "mazewall-tier-e-rootful:perf-20260919-external-profiler"
            } else {
                val configured = container.getValue("image").jsonPrimitive
                require(configured.isString) { "Experiment image must be a JSON string" }
                configured.content
            }
            require(image.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]+"))) { "Invalid experiment image" }
            val cpus = container?.let {
                val workload = it
                    .getValue("workload_cpu")
                    .jsonPrimitive
                    .takeUnless(JsonPrimitive::isString)
                    ?.intOrNull
                val collector = it
                    .getValue("collector_cpu")
                    .jsonPrimitive
                    .takeUnless(JsonPrimitive::isString)
                    ?.intOrNull
                require(workload != null && collector != null && workload >= 0 && collector >= 0 && workload != collector) {
                    "Experiment CPUs must be distinct nonnegative integers"
                }
                "$workload,$collector"
            }
            require(kind != "node-noise" || cpus == null || nodeNoise.cpu.toString() !in cpus.split(',')) {
                "Node-noise CPU must differ from measured CPUs"
            }
            val trustworthy = TierETrustworthySettings.load(values)
            val capacity = if (kind == "capacity") {
                TierEExperimentCapacityProfile.load(
                    document["capacity"] as? JsonObject ?: throw IllegalArgumentException("Missing capacity schedule"),
                    trustworthy,
                )
            } else {
                null
            }
            return TierEExperimentProfile(kind, trustworthy, nodeNoise, image, cpus, capacity)
        }
    }
}
