package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Read-only admission of a separately owned six-fork readiness receipt; it never qualifies native timing. */
internal object TierENativeTimingReadiness {
    fun verify(
        output: Path,
        revision: String,
        sourceDigest: String,
    ) {
        require(Files.isDirectory(output)) { "Native timing readiness receipt is missing" }
        require(Files.isRegularFile(output.resolve("diagnostic-terminal.json"))) { "Native timing readiness terminal is missing" }

        fun json(file: String): JsonObject = Json.parseToJsonElement(Files.readString(output.resolve(file))).jsonObject

        fun field(
            document: JsonObject,
            name: String,
        ): String =
            document[name]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing readiness field $name")

        fun sha(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

        val terminal = json("diagnostic-terminal.json")
        require(field(terminal, "status") == "READINESS_COMPLETE" && field(terminal, "policy_eligibility") == "false") {
            "Native timing readiness terminal is not complete and policy-ineligible"
        }
        val plan = json("plan.json")
        require(
            field(plan, "kind") == "NON_QUALIFYING_TIMING_READINESS" &&
                field(plan, "source_revision") == revision &&
                field(plan, "source_digest") == sourceDigest &&
                field(plan, "image_id") == "sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56" &&
                field(plan, "kernel") == "7.0.0-34-generic" &&
                field(plan, "kernel_config_sha256") == "e75c0adab6020e72844d7431cf801a02b94ed32efc251c3b0fb80c021d065180" &&
                field(plan, "cpu_set") == "0,2" &&
                field(plan, "samples") == "6" &&
                field(plan, "timeout_seconds_per_fork") == "180" &&
                field(plan, "campaign_timeout_minutes") == "20" &&
                field(plan, "policy_eligibility") == "false",
        ) { "Readiness plan differs from the new source or fixed environment" }
        TierENativeTimingDiagnostic.requireBinaryIdentities(
            field(plan, "original_agent_sha256"),
            field(plan, "diagnostic_agent_sha256"),
            field(plan, "workload_class_sha256"),
        )
        TierENativeTimingDiagnostic.requireToolchain(field(plan, "java"), field(plan, "compiler"))
        val preflight = Files.readAllLines(output.resolve("preflight.sha256"))
        require(
            preflight == listOf("${sha(output.resolve("plan.json"))}  plan.json", "${sha(output.resolve("schedule.jsonl"))}  schedule.jsonl"),
        ) { "Readiness source plan or schedule changed after acquisition" }
        TierENativeTimingDiagnostic.verifyExport(output, output.resolve("diagnostic"), 6)
        val schedule = TierENativeTimingDiagnostic.readinessSchedule()
        val declarations = Files.readAllLines(output.resolve("schedule.jsonl"))
        val observations = Files.readAllLines(output.resolve("observations.jsonl"))
        require(declarations.size == 6 && observations.size == 6) { "Readiness lacks six declared and six measured samples" }
        for (sample in schedule) {
            val row = Json.parseToJsonElement(observations[sample.index - 1]).jsonObject
            val declared = Json.parseToJsonElement(declarations[sample.index - 1]).jsonObject
            for (document in listOf(declared, row)) {
                require(
                    field(document, "index") == sample.index.toString() &&
                        field(document, "arm") == sample.arm.toString() &&
                        field(document, "mode") == sample.mode &&
                        field(document, "block") == "1",
                ) { "Readiness fork identity or execution order changed" }
            }
            require(field(row, "policy_eligibility") == "false") { "Readiness sample claims policy eligibility" }
            val prefix = "sample-${sample.index.toString().padStart(2, '0')}"
            val log = output.resolve("$prefix.log")
            require(field(row, "sample_log_sha256") == sha(log)) { "Readiness raw sample changed" }
            val parsed = TierENativeTimingDiagnostic.parse(sample.arm, sample.mode, Files.readString(log))
            require(field(row, "stack_ns") == parsed.stackNs.toString() && field(row, "workload_ns") == parsed.workloadNs.toString()) {
                "Readiness native sample does not match its reported measurement"
            }

            fun telemetry(name: String): TierENativeTimingDiagnostic.Telemetry {
                val raw = Files.readString(output.resolve("$prefix-$name.json")).trim()
                require(field(row, name) == raw) { "Readiness host telemetry was changed" }
                val value = Json.parseToJsonElement(raw).jsonObject
                return TierENativeTimingDiagnostic.requireTelemetry(
                    field(value, "cpu_stat"),
                    field(value, "cpu_max"),
                    field(value, "cpuset_effective"),
                    field(value, "host_cpu_pressure"),
                )
            }
            TierENativeTimingDiagnostic.requireProgress(telemetry("before"), telemetry("after"))
        }
    }
}
