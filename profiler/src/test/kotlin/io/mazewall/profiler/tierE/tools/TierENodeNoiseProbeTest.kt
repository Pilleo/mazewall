package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TierENodeNoiseProbeTest {
    @Test
    fun `baseline and attached observations remain paired within one diagnostic run`() {
        val output = Files.createTempDirectory("tier-e-noise-pair-")
        try {
            val calls = mutableListOf<String>()
            TierENodeNoiseProbe.runPairs(
                1,
                output,
                observe = { label ->
                    calls += label
                    TierENodeNoiseSample(10, 0.1, 0.2, 1.0)
                },
                attached = { _: Path -> calls += "attached-ready" },
            )
            assertEquals(listOf("baseline-1", "attached-ready", "attached-1"), calls)
            val summary = Json.parseToJsonElement(Files.readString(output.resolve("summary.json"))).jsonObject
            assertEquals("1", summary.getValue("pairs").toString())
            assertEquals("false", summary.getValue("policy_eligibility").toString())
            assertEquals(1, summary.getValue("records").jsonArray.size)
        } finally {
            output.toFile().deleteRecursively()
        }
    }
}
