package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class TierELongMatchedEstimatorTest {
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("docs/internals/evidence/tier-e-long-matched-controls/decision.json")) }

    @Test
    fun `both archived decisions retain their independent paired medians and bootstrap bounds`() {
        for (name in listOf("tier-e-long-matched-controls", "tier-e-long-matched-controls-corrected-20260925")) {
            val evidence = root.resolve("docs/internals/evidence/$name")
            val expected = Json.parseToJsonElement(Files.readString(evidence.resolve("decision.json"))).jsonObject
            val actual = TierELongMatchedEstimator.summarize(evidence.resolve("fork-results.jsonl"))
            assertEquals("COMPLETE", actual.getValue("metrics_status").jsonPrimitive.content, name)
            assertEquals(
                15,
                actual
                .getValue("complete_forks")
                .jsonPrimitive.content
                .toInt(),
                    name,
            )
            assertEquals(
                false,
                actual
                .getValue("policy_eligibility")
                .jsonPrimitive.content
                .toBoolean(),
                    name,
            )
            for (mode in listOf("full-stream", "unique-edge")) {
                val want = expected
                    .getValue("modes")
                    .jsonObject
                    .getValue(mode)
                    .jsonObject
                val got = actual
                    .getValue("modes")
                    .jsonObject
                    .getValue(mode)
                    .jsonObject
                for (metric in listOf("combined_cpu_ratio", "incremental_cpu_ns")) {
                    for (variant in listOf("v1", "v2", "hybrid")) {
                        for (field in listOf("median", "upper_95")) {
                            val target = want
                                .getValue(metric)
                                .jsonObject
                                .getValue(variant)
                                .jsonObject
                                .getValue(field)
                                .jsonPrimitive.content
                                .toDouble()
                            val observed = got
                                .getValue(metric)
                                .jsonObject
                                .getValue(variant)
                                .jsonObject
                                .getValue(field)
                                .jsonPrimitive.content
                                .toDouble()
                            assertEquals(target, observed, 1e-9 * maxOf(1.0, target), "$name/$mode/$metric/$variant/$field")
                        }
                    }
                }
                val expectedRatio = want.getValue("hybrid_over_v1_incremental_ratio").jsonObject
                val actualRatio = got.getValue("hybrid_over_v1_incremental_ratio").jsonObject
                assertEquals(
                    expectedRatio
                        .getValue("median")
                        .jsonPrimitive.content
                        .toDouble(),
                    actualRatio
                        .getValue("median")
                        .jsonPrimitive.content
                        .toDouble(),
                    1e-12,
                )
                assertEquals(
                    expectedRatio
                        .getValue("upper_95")
                        .jsonPrimitive.content
                        .toDouble(),
                    actualRatio
                        .getValue("upper_95")
                        .jsonPrimitive.content
                        .toDouble(),
                    1e-12,
                )
            }
        }
    }

    @Test
    fun `a truncated run cannot claim a confidence bound`() {
        val evidence = root.resolve("docs/internals/evidence/tier-e-long-matched-controls")
        val sample = Files.createTempFile("tier-e-104-", ".jsonl")
        try {
            Files.write(sample, Files.readAllLines(evidence.resolve("fork-results.jsonl")).dropLast(1))
            val result = TierELongMatchedEstimator.summarize(sample)
            assertEquals("INCONCLUSIVE", result.getValue("metrics_status").jsonPrimitive.content)
            val mode = result
                .getValue("modes")
                .jsonObject
                .getValue("full-stream")
                .jsonObject
            assertEquals(
                "null",
                mode
                .getValue("combined_cpu_ratio")
                .jsonObject
                .getValue("v1")
                .jsonObject
                .getValue("upper_95")
                .toString(),
            )
            assertTrue(result.getValue("incomplete_reasons").toString().contains("missing_arm"))
        } finally {
            Files.deleteIfExists(sample)
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
        "missing_fork", "seed_drift", "source_identity_mismatch", "operations_mismatch",
        "coverage_failed", "terminal_incomplete", "boundary_changed", "cpu_not_finite",
        "nonpositive_v1_denominator", "source_identity_missing", "unexpected_arm", "artifact_errors", "timed_out",
    ],
    )
    fun `malformed fork rows cannot produce decision quality bounds`(reason: String) {
        val evidence = root.resolve("docs/internals/evidence/tier-e-long-matched-controls")
        val original = Files
            .readAllLines(evidence.resolve("fork-results.jsonl"))
            .map { Json.parseToJsonElement(it).jsonObject }
            .toMutableList()
        val baseline = original.indexOfFirst { it.getValue("fork").jsonPrimitive.content == "1" && it.getValue("arm").jsonPrimitive.content == "baseline" }
        val profiled = original.indexOfFirst { it.getValue("fork").jsonPrimitive.content == "1" && it.getValue("arm").jsonPrimitive.content == "dictionary-full-stream-v1" }

        fun change(
            index: Int,
            field: String,
            value: kotlinx.serialization.json.JsonElement,
        ) {
            val fields = original[index].toMutableMap()
            fields[field] = value
            original[index] = JsonObject(fields)
        }
        when (reason) {
            "missing_fork" -> original.removeAll { it.getValue("fork").jsonPrimitive.content == "15" }
            "seed_drift" -> change(baseline, "seed", JsonPrimitive(999))
            "source_identity_mismatch" -> change(baseline, "image_id", JsonPrimitive("another-image"))
            "operations_mismatch" -> change(baseline, "declared_operations", JsonPrimitive(72))
            "coverage_failed" -> change(profiled, "coverage_verdict", JsonPrimitive("FAILED"))
            "terminal_incomplete" -> change(profiled, "terminal_integrity", JsonPrimitive("INCOMPLETE"))
            "boundary_changed" -> change(profiled, "collector_sample_boundary", JsonPrimitive("wrong-boundary"))
            "cpu_not_finite" -> change(profiled, "target_cpu_ns", JsonPrimitive("NaN"))
            "nonpositive_v1_denominator" -> {
                val base = original[baseline]
                    .getValue("target_cpu_ns")
                    .jsonPrimitive.content
                    .toLong()
                change(profiled, "target_cpu_ns", JsonPrimitive(1))
                change(profiled, "collector_cpu_ns", JsonPrimitive(base - 1))
            }
            "source_identity_missing" -> for (index in original.indices) change(index, "source_digest", JsonNull)
            "unexpected_arm" -> original += JsonObject(original[profiled] + ("arm" to JsonPrimitive("unexpected-arm")))
            "artifact_errors" -> change(profiled, "artifact_errors", JsonArray(listOf(JsonPrimitive("unreadable"))))
            "timed_out" -> change(profiled, "timed_out", JsonPrimitive(true))
        }
        val sample = Files.createTempFile("tier-e-negative-", ".jsonl")
        try {
            Files.write(sample, original.map(JsonObject::toString))
            val decision = TierELongMatchedEstimator.summarize(sample)
            assertEquals("INCONCLUSIVE", decision.getValue("metrics_status").jsonPrimitive.content, reason)
            assertTrue(decision.getValue("incomplete_reasons").toString().contains("\"$reason\""), reason)
            assertEquals(
                "null",
                decision
                    .getValue("modes")
                    .jsonObject
                    .getValue("full-stream")
                    .jsonObject
                .getValue("combined_cpu_ratio")
                    .jsonObject
                    .getValue("v1")
                    .jsonObject
                    .getValue("upper_95")
                    .toString(),
                    reason,
            )
        } finally {
            Files.deleteIfExists(sample)
        }
    }
}
