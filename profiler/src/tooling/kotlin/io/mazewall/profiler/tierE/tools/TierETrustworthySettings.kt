package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Resolved operator inputs for the rootful, split build/run Tier E benchmark. */
public data class TierETrustworthySettings(
    val runLevel: String,
    val scope: String,
    val workload: String,
    val lifecycleScenario: String,
    val forks: Int,
    val batches: Int,
    val warmups: Int,
    val iterations: Int,
    val decisionForks: Int,
    val requireDecisionForks: Boolean,
    val childTimeoutSeconds: Int,
    val workerTimeoutSeconds: Int,
    val userNotifChildTimeoutSeconds: Int,
    val perfRecord: Boolean,
    val allocationRecord: Boolean,
    val perfJitMap: Boolean,
    val verifyBuild: Boolean,
    val calibration: Boolean,
    val nativeBuildType: String,
    val sustainedRate: Int,
    val sustainedLoad: String,
    val canonicalCache: String,
    val captureProfile: String,
    val attachmentBackend: String,
    val kernelCoverageMode: String,
    val attachmentShape: String,
    val receiptClock: String,
    val perfCallGraph: String,
    val benchmarkJavaOptions: String,
    val fixtureChildTerminationMillis: Int,
) {
    public fun guestEnvironment(): Map<String, String> =
        mapOf(
        "RUN_LEVEL" to runLevel,
            "BENCHMARK_SCOPE" to scope,
            "BENCHMARK_WORKLOAD" to workload,
        "LIFECYCLE_SCENARIO" to lifecycleScenario,
            "FORKS" to forks.toString(),
            "BATCHES" to batches.toString(),
        "WARMUPS" to warmups.toString(),
            "ITERATIONS" to iterations.toString(),
            "DECISION_FORKS" to decisionForks.toString(),
        "REQUIRE_DECISION_FORKS" to requireDecisionForks.toString(),
            "CHILD_TIMEOUT_SECONDS" to childTimeoutSeconds.toString(),
        "USER_NOTIF_WORKER_TIMEOUT_SECONDS" to workerTimeoutSeconds.toString(),
        "USER_NOTIF_CHILD_TIMEOUT_SECONDS" to userNotifChildTimeoutSeconds.toString(),
        "PERF_RECORD" to perfRecord.toString(),
            "ALLOCATION_RECORD" to allocationRecord.toString(),
        "PERF_JIT_MAP" to perfJitMap.toString(),
            "VERIFY_BUILD" to verifyBuild.toString(),
            "CALIBRATION" to calibration.toString(),
        "NATIVE_BUILD_TYPE" to nativeBuildType,
            "SUSTAINED_RATE_PER_SECOND" to sustainedRate.toString(),
        "SUSTAINED_LOAD" to sustainedLoad,
            "CANONICAL_CACHE" to canonicalCache,
            "CAPTURE_PROFILE" to captureProfile,
        "SYS_ENTER_ATTACHMENT_BACKEND" to attachmentBackend,
            "KERNEL_COVERAGE_MODE" to kernelCoverageMode,
        "SYS_ENTER_ATTACHMENT_SHAPE" to attachmentShape,
            "COLLECTOR_RECEIPT_CLOCK" to receiptClock,
        "PERF_CALL_GRAPH" to perfCallGraph,
            "BENCHMARK_JAVA_OPTIONS" to benchmarkJavaOptions,
        "FIXTURE_CHILD_TERMINATION_AFTER_MILLIS" to fixtureChildTerminationMillis.toString(),
    ).mapKeys { (key, _) -> "MAZEWALL_TIER_E_$key" }

    public fun resolvedJson(): JsonObject =
        buildJsonObject {
        put("schema_version", 1)
        put("run_level", runLevel)
        put("scope", scope)
        put("workload", workload)
        put("lifecycle_scenario", lifecycleScenario)
        put("forks", forks)
        put("batches", batches)
        put("warmups", warmups)
        put("iterations", iterations)
        put("decision_forks", decisionForks)
        put("require_decision_forks", requireDecisionForks)
        put("child_timeout_seconds", childTimeoutSeconds)
        put("worker_timeout_seconds", workerTimeoutSeconds)
        put("user_notif_child_timeout_seconds", userNotifChildTimeoutSeconds)
        put("perf_record", perfRecord)
        put("allocation_record", allocationRecord)
        put("perf_jit_map", perfJitMap)
        put("verify_build", verifyBuild)
        put("calibration", calibration)
        put("native_build_type", nativeBuildType)
        put("sustained_rate_per_second", sustainedRate)
        put("sustained_load", sustainedLoad)
        put("canonical_cache", canonicalCache)
        put("capture_profile", captureProfile)
        put("sys_enter_attachment_backend", attachmentBackend)
        put("kernel_coverage_mode", kernelCoverageMode)
        put("sys_enter_attachment_shape", attachmentShape)
        put("collector_receipt_clock", receiptClock)
        put("perf_call_graph", perfCallGraph)
        put("benchmark_java_options", benchmarkJavaOptions)
        put("fixture_child_termination_after_millis", fixtureChildTerminationMillis)
        put("policy_eligibility", false)
    }

    public companion object {
        private val scopes = setOf(
            "full",
            "tier-e-comparable",
            "tier-e-calibration",
            "tier-e-cache-ab",
            "tier-e-cache-paired",
            "tier-e-publication",
            "tier-e-dictionary-publication",
            "tier-e-capture-profile",
            "tier-e-capture-timing",
            "tier-e-ring-decode",
            "tier-e-readiness",
            "tier-e-poll-backoff",
            "tier-e-ring-argument-list",
            "tier-e-ring-argument-view",
            "tier-e-task-identity",
            "tier-e-known-join",
            "tier-e-ring-submission-control",
            "tier-e-retention-exhaustion",
            "tier-e-owned-arguments",
            "tier-e-ready-transfer",
            "tier-e-collector-append",
            "tier-e-unique-edge-observer",
            "tier-e-marker-transport",
            "tier-e-marker-transport-comparable",
            "tier-e-lifecycle",
            "tier-e-production-selection",
            "tier-e-sustained-collector",
            "tier-e-attachment-diagnostic",
            "tier-e-coverage-discovery",
        )
        private val workloads = setOf(
            "application",
            "filesystem_metadata",
            "buffered_file_io",
            "loopback_socket",
            "oracle",
            "syscall_dense",
            "mixed_cpu_io",
            "bursty_io",
            "calibration_cpu",
            "stack_diverse",
            "class_churn",
        )
        private val lifecycles = setOf(
            "normal",
            "detach_while_active",
            "callback_suppression",
            "terminal_while_active",
            "capture_failure",
            "exec_transition",
        )

        public fun load(environment: Map<String, String> = System.getenv()): TierETrustworthySettings {
            fun value(
                name: String,
                default: String,
            ) = environment["MAZEWALL_TIER_E_$name"] ?: default

            fun number(
                name: String,
                default: Int,
                minimum: Int = 1,
            ): Int =
                value(name, default.toString())
                    .toIntOrNull()
                ?.takeIf { it >= minimum } ?: throw IllegalArgumentException("Invalid Tier E $name")

            fun flag(
                name: String,
                default: Boolean,
            ): Boolean =
                when (value(name, default.toString())) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("Invalid Tier E $name")
            }

            fun choice(
                name: String,
                default: String,
                allowed: Set<String>,
            ): String =
                value(name, default)
                .also { require(it in allowed) { "Unknown Tier E $name: $it" } }
            val level = choice("RUN_LEVEL", "development", setOf("smoke", "development", "decision", "custom"))
            val scope = choice("BENCHMARK_SCOPE", "full", scopes)
            val forks = number(
                "FORKS",
                if (level == "smoke") {
                1
            } else if (level == "decision") {
                15
            } else {
                5
            },
            )
            val decisionForks = number("DECISION_FORKS", 15)
            val requireDecision = flag("REQUIRE_DECISION_FORKS", false) || level == "decision"
            require(!requireDecision || forks >= decisionForks) { "Decision run requires at least $decisionForks forks" }
            val workerTimeout = number("USER_NOTIF_WORKER_TIMEOUT_SECONDS", 480)
            val userChildTimeout = number("USER_NOTIF_CHILD_TIMEOUT_SECONDS", 540)
            require(userChildTimeout > workerTimeout) { "USER_NOTIF child timeout must exceed worker timeout" }
            val perf = flag("PERF_RECORD", false)
            val allocation = flag("ALLOCATION_RECORD", false)
            require(!perf || !allocation) { "CPU perf and allocation perf are mutually exclusive" }
            val rate = number("SUSTAINED_RATE_PER_SECOND", 0, 0)
            require(scope != "tier-e-sustained-collector" || rate > 0) { "Sustained collector requires a positive rate" }
            val capture = value("CAPTURE_PROFILE", "")
            require(
                capture.isEmpty() ||
                capture == "jdk25-unix-openat0" ||
                capture.matches(Regex("capture-bindings=[a-z0-9-]+(\\+[a-z0-9-]+)*")),
            ) { "Invalid Tier E capture profile" }
            require(scope != "tier-e-capture-profile" || capture.isEmpty()) { "Capture-profile scope owns its selection" }
            val nativeType = value("NATIVE_BUILD_TYPE", "Release")
            require(nativeType.matches(Regex("[A-Za-z0-9_]+"))) { "Invalid Tier E native build type" }
            return TierETrustworthySettings(
                level,
                scope,
                choice("BENCHMARK_WORKLOAD", "oracle", workloads),
                choice("LIFECYCLE_SCENARIO", "normal", lifecycles),
                forks,
                number("BATCHES", if (level == "smoke") 3 else 10),
                number("WARMUPS", 3, 0),
                number("ITERATIONS", 100),
                decisionForks,
                requireDecision,
                number("CHILD_TIMEOUT_SECONDS", 180),
                workerTimeout,
                userChildTimeout,
                perf,
                allocation,
                flag("PERF_JIT_MAP", true),
                flag("VERIFY_BUILD", false),
                flag("CALIBRATION", true),
                nativeType,
                rate,
                choice("SUSTAINED_LOAD", "below_capacity", setOf("below_capacity", "near_capacity", "intentional_overload")),
                choice("CANONICAL_CACHE", "enabled", setOf("enabled", "disabled")),
                capture,
                value("SYS_ENTER_ATTACHMENT_BACKEND", "auto"),
                value("KERNEL_COVERAGE_MODE", "diagnostic-update"),
                value("SYS_ENTER_ATTACHMENT_SHAPE", "main-plus-audit"),
                choice("COLLECTOR_RECEIPT_CLOCK", "disabled", setOf("disabled", "monotonic")),
                choice("PERF_CALL_GRAPH", "dwarf", setOf("dwarf", "fp", "lbr")),
                value("BENCHMARK_JAVA_OPTIONS", ""),
                number("FIXTURE_CHILD_TERMINATION_AFTER_MILLIS", 0, 0),
            )
        }
    }
}
