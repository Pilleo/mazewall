package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

public data class TierELegacyFixtureLaunch(
    val configuration: TierERunConfiguration,
    val seed: Int,
    val pinCpus: Boolean,
    val benchmarkPhases: Boolean,
    val workloadProfile: String,
) {
    public companion object {
        public fun translate(environment: Map<String, String>): TierELegacyFixtureLaunch {
            fun flag(
                name: String,
                default: String = "0",
            ): String = environment[name]?.takeIf { it.isNotBlank() } ?: default

            fun binary(name: String): Boolean =
                when (flag(name)) {
                "0" -> false
                "1" -> true
                else -> throw IllegalArgumentException("Invalid Tier E binary flag: $name")
            }

            fun optionalInt(name: String): Int? =
                environment[name]?.takeIf(String::isNotBlank)?.let { raw ->
                raw.toIntOrNull() ?: throw IllegalArgumentException("Invalid Tier E integer: $name")
            }
            val rejected = listOf(
                "TIER_E_SPLIT_DISABLE_MARKER_PROOF" to "1",
            ).filter { (name, active) -> environment[name] == active }
            require(rejected.isEmpty()) { "Kotlin fixture launcher does not own ${rejected.joinToString { it.first }}" }
            val profile = environment["TIER_E_SPLIT_WORKLOAD_PROFILE"] ?: "syscall_dense"
            require(profile in setOf("syscall_dense", "stack_diverse", "class_churn")) { "Unsupported Tier E workload profile: $profile" }
            val liveDefinitions = binary("TIER_E_SPLIT_LIVE_DEFINITIONS")
            val liveAdmission = binary("TIER_E_SPLIT_ADMIT_LIVE_SNAPSHOTS")
            require(liveDefinitions == liveAdmission) { "Live definitions and snapshot admission must match" }
            val phases = binary("TIER_E_SPLIT_BENCHMARK_PHASES")
            val coverage = binary("TIER_E_SPLIT_COVERAGE")
            val benchmark = binary("TIER_E_SPLIT_BENCHMARK")
            val baseline = binary("TIER_E_SPLIT_COVERAGE_BASELINE")
            val simulated = binary("TIER_E_SPLIT_SIMULATED_PID_REUSE")
            val real = binary("TIER_E_SPLIT_REAL_PID_REUSE")
            val identity = environment["TIER_E_SPLIT_NEGATIVE_IDENTITY_CASE"]?.takeIf { it.isNotBlank() }
            require(!(simulated && identity != null) && !(real && (simulated || identity != null))) {
                "Tier E identity cases are mutually exclusive"
            }
            val socket = when (val access = flag("TIER_E_SPLIT_SOCKET_ACCESS", "group")) {
                "group" -> "group"
                "owner" -> "owner-only"
                else -> error("Unknown Tier E socket access: $access")
            }
            if (real) {
                require(
                    !coverage &&
                !benchmark &&
                !baseline &&
                socket == "group" &&
                flag("TIER_E_SPLIT_MARKER_VERSION", "v1") == "v2" &&
                flag("TIER_E_SPLIT_RECOVERY_MODE", "disabled") == "recover",
                ) {
                "Real PID reuse requires v2 hybrid registered workflow"
            }
            }
            val operations = flag("TIER_E_SPLIT_COVERAGE_OPERATIONS", "72").toIntOrNull()
                ?: error("Invalid Tier E operations")
            val seed = optionalInt("TIER_E_COVERAGE_SEED") ?: 23063
            val warmups = flag("TIER_E_SPLIT_BENCH_WARMUPS", "3").toIntOrNull() ?: error("Invalid warmups")
            val batches = flag("TIER_E_SPLIT_BENCH_BATCHES", "10").toIntOrNull() ?: error("Invalid batches")
            val iterations = flag("TIER_E_SPLIT_BENCH_ITERATIONS", "100").toIntOrNull() ?: error("Invalid iterations")
            val workloadCpu = optionalInt("TIER_E_SPLIT_WORKLOAD_CPU")
            val collectorCpu = optionalInt("TIER_E_SPLIT_COLLECTOR_CPU")
            require((workloadCpu == null) == (collectorCpu == null)) { "Tier E CPU pin requires both workload and collector CPUs" }
            val fault = optionalInt("TIER_E_SPLIT_FAULT_RING_DROP_SYSCALL")
            val document = buildJsonObject {
                put("schema_version", 2)
                put(
                    "container",
                    buildJsonObject {
                    put("engine", flag("TIER_E_CONTAINER_ENGINE", "docker"))
                    put("uri", environment["MAZEWALL_DOCKER_HOST"]?.let(::JsonPrimitive) ?: JsonNull)
                    put("image", flag("TIER_E_DOCKER_IMAGE", "mazewall-tier-e-rootful:latest"))
                },
                )
                put(
                    "fixture",
                    buildJsonObject {
                    put("coverage", coverage)
                    put("operations", operations)
                    put("emission_mode", flag("TIER_E_SPLIT_EMISSION_MODE", "full-stream"))
                    put("recovery_mode", flag("TIER_E_SPLIT_RECOVERY_MODE", "disabled"))
                },
                )
                put(
                    "measurement",
                    buildJsonObject {
                    put("forks", 1)
                    put("workload_cpu", workloadCpu ?: 0)
                    put("collector_cpu", collectorCpu ?: 1)
                    put("seed_start", seed)
                    put("warmups", warmups)
                    put("batches", batches)
                    put("iterations", iterations)
                },
                )
                put(
                    "scenario",
                    buildJsonObject {
                    put("marker_version", flag("TIER_E_SPLIT_MARKER_VERSION", "v1"))
                    put("marker_backend", flag("TIER_E_SPLIT_MARKER_BACKEND", "uprobe"))
                    put("stack_transport", flag("TIER_E_SPLIT_STACK_TRANSPORT", "dictionary"))
                    put("socket_access", socket)
                    put("fault_syscall", fault?.let(::JsonPrimitive) ?: JsonNull)
                    put("negative_artifact", environment["TIER_E_SPLIT_NEGATIVE_ARTIFACT_CASE"]?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
                    put(
                        "identity_case",
                        (
                            identity ?: if (real) {
                        "real-reuse"
                    } else if (simulated) {
                        "simulated-reuse"
                    } else {
                        null
                    }
                        )?.let(::JsonPrimitive) ?: JsonNull,
                    )
                    put("live_snapshots", liveDefinitions)
                    put("baseline", baseline)
                    put("benchmark", benchmark)
                    put("allow_incomplete", binary("TIER_E_SPLIT_ALLOW_INCOMPLETE"))
                    put("delayed_artifact", binary("TIER_E_SPLIT_DELAYED_ARTIFACT"))
                },
                )
                put(
                    "build",
                    buildJsonObject {
                    put("native_build_dir", environment["TIER_E_SPLIT_NATIVE_BUILD_DIR"]?.let(::JsonPrimitive) ?: JsonNull)
                    put("reuse", binary("TIER_E_SPLIT_REUSE_BUILD"))
                },
                )
                put(
                    "deadlines",
                    buildJsonObject {
                    put("arm_seconds", 600)
                    put("terminal_seconds", 90)
                    put("stop_seconds", 10)
                },
                )
                put(
                    "artifacts",
                    buildJsonObject {
                    put("output_root", JsonNull)
                    put("fresh", true)
                    put("retain_on_failure", false)
                },
                )
            }.toString()
            val profileFile = Files.createTempFile("tier-e-legacy-profile-", ".json")
            return try {
                Files.writeString(profileFile, document)
                TierELegacyFixtureLaunch(
                    TierERunConfiguration.load(profileFile, environment = emptyMap()),
                    seed,
                    pinCpus = workloadCpu != null,
                    benchmarkPhases = phases,
                    workloadProfile = profile,
                )
            } finally {
                Files.deleteIfExists(profileFile)
            }
        }
    }
}
