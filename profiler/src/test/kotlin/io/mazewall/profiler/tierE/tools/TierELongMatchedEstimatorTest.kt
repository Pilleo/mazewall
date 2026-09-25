package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
}
