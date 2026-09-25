package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.file.Files
import java.nio.file.Path

/** Developer fixture classification; all selected terminal conditions fail closed. */
public object TierEFixtureVerdict {
    public fun validate(
        directory: Path,
        scenario: String,
    ): String {
        fun read(name: String): JsonObject =
            Json.parseToJsonElement(Files.readString(directory.resolve(name))) as? JsonObject
            ?: throw IllegalArgumentException("Expected JSON object: $name")
        val terminal = read("terminal-report.json")

        fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.content

        fun JsonObject.truth(key: String): Boolean =
            when (get(key)?.toString()) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Missing/invalid boolean: $key")
        }

        fun requireTerminal(
            integrity: String,
            reason: String? = null,
        ) {
            require(terminal.text("evidence_integrity") == integrity) { "Unexpected evidence integrity: ${terminal.text("evidence_integrity")}" }
            if (reason != null) require(terminal.text("terminal_reason") == reason) { "Unexpected terminal reason" }
            require(!terminal.truth("policy_eligibility")) { "Fixture cannot qualify policy" }
        }

        fun coverage(): JsonObject =
            read("coverage-verdict.json").also {
            require(!it.truth("policy_eligibility")) { "Coverage cannot qualify policy" }
            require((it["wrong_edges"] as? JsonArray)?.isEmpty() == true) { "Contradictory coverage edges" }
        }
        val incomplete = "evidence_integrity=INCOMPLETE\npolicy_eligibility=false\n"
        return when (scenario) {
            "normal" -> {
                requireTerminal("COMPLETE")
                for (field in listOf("capture_domain_validity", "transport_completeness", "attribution_coverage", "dictionary_integrity", "lifecycle_validity")) {
                    require(terminal.truth(field)) { "Incomplete terminal dimension: $field" }
                }
                "evidence_integrity=COMPLETE\ncapture_domain_validity=PASS\ntransport_completeness=PASS\n" +
                    "attribution_coverage=PASS\ndictionary_integrity=PASS\nlifecycle_validity=PASS\npolicy_eligibility=false\n"
            }
            "fault" -> {
                requireTerminal("INCOMPLETE")
                require(!terminal.truth("transport_completeness") && !terminal.truth("attribution_coverage") && terminal.truth("lifecycle_validity")) {
                    "Unexpected ring-drop terminal dimensions"
                }
                val recovery = terminal["recovery_evidence"] as? JsonObject ?: throw IllegalArgumentException("Missing recovery evidence")

                fun count(name: String): Int = (recovery[name] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("Missing $name")
                require(count("insertion_failures") == 0 && count("recovered_edges") >= 24 && count("confirmed_ring_loss_recoveries") >= 24) {
                    "Insufficient ring-drop recovery"
                }
                require(coverage().text("coverage_verdict") == "PASS") { "Coverage did not recover exact paths" }
                val edges = read("coverage-manifest.json")["edges"] as? JsonArray ?: throw IllegalArgumentException("Missing coverage edges")
                val singletons = edges.filter { edge ->
                    val path = ((edge as? JsonObject)?.get("workload_path") as? JsonPrimitive)?.intOrNull
                    path != null && path in 48..71
                }
                require(singletons.size == 24 && singletons.all { (it as JsonObject).text("discovery_source") == "map" }) {
                    "Singleton coverage was not recovered from map"
                }
                "evidence_integrity=INCOMPLETE\ncoverage_verdict=PASS\nring_transport=INCOMPLETE\nsingleton_source=MAP\npolicy_eligibility=false\n"
            }
            "allow-incomplete" -> {
                val status = coverage().text("coverage_verdict")
                val integrity = terminal.text("evidence_integrity")
                require((integrity == "COMPLETE" && status == "PASS") || (integrity == "INCOMPLETE" && status == "INCOMPLETE")) {
                    "Contradictory allowed-incomplete coverage and terminal"
                }
                require(!terminal.truth("policy_eligibility"))
                "evidence_integrity=$integrity\ncoverage_verdict=$status\npolicy_eligibility=false\n"
            }
            "simulated-reuse", "real-reuse" -> {
                requireTerminal("INCOMPLETE", "PROCESS_IDENTITY_REUSED")
                require(!terminal.truth("lifecycle_validity") && !terminal.truth("transport_completeness"))
                incomplete + "terminal_reason=PROCESS_IDENTITY_REUSED\n"
            }
            "stale" -> {
                requireTerminal("INCOMPLETE", "STALE_PROCESS_IDENTITY")
                require(!terminal.truth("lifecycle_validity") && terminal.text("resolved_syscalls") == "0")
                incomplete + "terminal_reason=STALE_PROCESS_IDENTITY\n"
            }
            "marker-before-workload" -> {
                requireTerminal("INCOMPLETE", "MARKER_VERSION_MISMATCH")
                require(!terminal.truth("lifecycle_validity") && terminal.text("resolved_syscalls") == "0")
                val target = Files.readString(directory.resolve("target.log"))
                require("attach failed" in target && "tier-e-coverage-target: operations=" !in target)
                "marker_mismatch=REJECTED_BEFORE_WORKLOAD\n$incomplete"
            }
            "marker" -> {
                requireTerminal("INCOMPLETE")
                require(!terminal.truth("lifecycle_validity") && coverage().text("coverage_verdict") != "PASS")
                "marker_mismatch=REJECTED\n$incomplete"
            }
            else -> {
                require(
                    scenario.startsWith("negative:") &&
                    scenario.substringAfter(':') in
                    setOf("missing", "malformed", "symlink", "unclosed", "session-mismatch"),
                ) {
                    "Unsupported Tier E fixture verdict scenario: $scenario"
                }
                requireTerminal("INCOMPLETE")
                require(terminal.text("resolved_syscalls") == "0") { "Negative artifact resolved syscalls" }
                require((terminal["failed_dimensions"] as? JsonArray)?.isNotEmpty() == true) {
                    "Negative artifact requires failed terminal dimensions"
                }
                incomplete + "negative_artifact_case=${scenario.substringAfter(':')}\n"
            }
        }
    }
}
