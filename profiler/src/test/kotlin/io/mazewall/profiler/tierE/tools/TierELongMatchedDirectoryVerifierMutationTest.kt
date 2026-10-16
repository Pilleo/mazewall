package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class TierELongMatchedDirectoryVerifierMutationTest {
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("docs/internals/evidence/tier-e-long-matched-controls/fork-results.jsonl")) }
    private val evidence = root.resolve("docs/internals/evidence/tier-e-long-matched-controls")

    private fun fixture(): Path {
        val dir = Files.createTempDirectory("tier-e-kotlin-artifacts-")
        for (file in listOf("fork-results.jsonl", "run-manifest.json", "source-state-before.json", "source-state-after.json", "terminal.json")) {
            Files.copy(evidence.resolve(file), dir.resolve(file))
        }
        val rows = Files.readAllLines(evidence.resolve("fork-results.jsonl")).map { Json.parseToJsonElement(it) as JsonObject }
        for (row in rows) {
            val arm = row.getValue("arm").jsonPrimitive.content
            val fork = row
                .getValue("fork")
                .jsonPrimitive.content
                .toInt()
            val output = dir.resolve("$arm/fork-$fork")
            Files.createDirectories(output)

            fun write(
                name: String,
                value: JsonObject,
            ) = Files.writeString(output.resolve(name), value.toString() + "\n")
            val targetPid = 5_000 + fork
            val starttime = 9_100_000 + fork
            write(
                "arm-result.json",
                buildJsonObject {
                put("schema_version", 1)
                put("fork", fork)
                put("arm", arm)
                put("status", "COMPLETE")
                put("fixture_exit", 0)
                put("timed_out", false)
                put("seed", 23062 + fork)
                put("policy_eligibility", false)
            },
            )
            write(
                "coverage-oracle.json",
                buildJsonObject {
                put("schema_version", 1)
                put("seed", 23062 + fork)
                put("declared_operations", 75000)
                put(
                    "paths",
                    JsonArray(
                        (0..71).map { path ->
                    buildJsonObject {
                    put("path", path)
                    put(
                        "category",
                        if (path < 24) {
                        "common"
                    } else if (path < 48) {
                        "infrequent"
                    } else {
                        "singleton"
                    },
                    )
                    put(
                        "frequency",
                        if (path < 24) {
                        3000
                    } else if (path < 48) {
                        124
                    } else {
                        1
                    },
                    )
                    put("method", "path${path.toString().padStart(2, '0')}")
                    put("syscall_number", 257)
                }
                },
                    ),
                )
            },
            )
            write(
                "target-cpu.json",
                buildJsonObject {
                put("schema_version", 1)
                put("version", 1)
                put("host_pid", targetPid)
                put("starttime", starttime)
                put("unit", "ns")
                put("sample_boundary", "after_workload_before_exit")
                put("target_cpu_ns", row.getValue("target_cpu_ns"))
            },
            )
            if (arm == "baseline") {
                write(
                    "summary.json",
                    buildJsonObject {
                    put("mode", "baseline")
                    put("target_exit", 0)
                    put("policy_eligibility", false)
                },
                )
            } else {
                val coverage = buildJsonObject {
                    put("coverage_verdict", "PASS")
                    put("expected_edges", 72)
                    put("recovered_edges", 72)
                    put("admission_errors", JsonArray(emptyList()))
                    put("missing_paths", JsonArray(emptyList()))
                    put("wrong_edges", JsonArray(emptyList()))
                    put("policy_eligibility", false)
                }
                val terminal = buildJsonObject {
                    put("registration_id", "split-fixture")
                    put("session_epoch", 19802)
                    put("target_pid", targetPid)
                    put("process_incarnation", starttime)
                    put("evidence_integrity", "COMPLETE")
                    put("terminal_reason", "COMPLETE")
                    for (field in listOf("capture_domain_validity", "transport_completeness", "attribution_coverage", "dictionary_integrity", "lifecycle_validity")) put(field, true)
                    put("failed_dimensions", JsonArray(emptyList()))
                    put("policy_eligibility", false)
                }
                write("coverage-verdict.json", coverage)
                write("terminal-report.json", terminal)
                write(
                    "collector-process-metrics.json",
                    buildJsonObject {
                    put("cpu_ticks", 100)
                    put("cpu_ns", row.getValue("collector_cpu_ns"))
                    put("unit", "ns")
                    put("cpu_time_basis", "process_ticks_since_start")
                    put("sample_boundary", "after_terminal_report_before_shutdown")
                },
                )
                write(
                    "summary.json",
                    buildJsonObject {
                    put("coverage", coverage)
                    put("terminal", terminal)
                    put("policy_eligibility", false)
                },
                )
            }
        }
        return dir
    }

    @Test
    fun `complete synthetic 105-arm raw directory passes without a container engine`() {
        val dir = fixture()
        try {
            val result = TierELongMatchedDirectoryVerifier.verify(dir)
            assertEquals("PASS", result.verdict, result.errors.joinToString("\n"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
        "missing-result", "corrupt-terminal", "empty-summary", "incomplete-result", "wrong-arm",
        "wrong-coverage", "wrong-target-cpu", "wrong-collector-cpu", "failed-terminal",
        "bad-oracle", "baseline-collector", "unexpected-arm-dir",
    ],
    )
    fun `mutated raw arm evidence fails closed`(scenario: String) {
        val dir = fixture()
        try {
            val profile = dir.resolve("dictionary-full-stream-v1/fork-1")

            fun change(
                file: String,
                field: String,
                value: kotlinx.serialization.json.JsonElement,
            ) {
                val path = profile.resolve(file)
                val obj = (Json.parseToJsonElement(Files.readString(path)) as JsonObject).toMutableMap()
                obj[field] = value
                Files.writeString(path, JsonObject(obj).toString())
            }
            when (scenario) {
                "missing-result" -> Files.delete(profile.resolve("arm-result.json"))
                "corrupt-terminal" -> Files.writeString(profile.resolve("terminal-report.json"), "{broken")
                "empty-summary" -> Files.writeString(profile.resolve("summary.json"), "{}")
                "incomplete-result" -> change("arm-result.json", "status", JsonPrimitive("INCOMPLETE"))
                "wrong-arm" -> change("arm-result.json", "arm", JsonPrimitive("dictionary-full-stream-v2"))
                "wrong-coverage" -> change("coverage-verdict.json", "recovered_edges", JsonPrimitive(71))
                "wrong-target-cpu" -> change("target-cpu.json", "target_cpu_ns", JsonPrimitive(1))
                "wrong-collector-cpu" -> change("collector-process-metrics.json", "cpu_ns", JsonPrimitive(1))
                "failed-terminal" -> {
                    change("terminal-report.json", "dictionary_integrity", JsonPrimitive(false))
                    val summary = Json.parseToJsonElement(Files.readString(profile.resolve("summary.json"))) as JsonObject
                    val updated = summary.toMutableMap()
                    updated["terminal"] = Json.parseToJsonElement(Files.readString(profile.resolve("terminal-report.json")))
                    Files.writeString(profile.resolve("summary.json"), JsonObject(updated).toString())
                }
                "bad-oracle" -> change("coverage-oracle.json", "paths", JsonArray(List(72) { JsonPrimitive(0) }))
                "baseline-collector" -> Files.writeString(dir.resolve("baseline/fork-1/collector-process-metrics.json"), "{}")
                "unexpected-arm-dir" -> Files.createDirectories(dir.resolve("wrong-arm/fork-1"))
            }
            val result = TierELongMatchedDirectoryVerifier.verify(dir)
            assertEquals("INCOMPLETE", result.verdict, scenario)
            assertTrue(result.errors.isNotEmpty(), scenario)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
