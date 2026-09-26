package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class TierENodeNoiseProbeTest {
    @Test
    fun `later diagnostic failure preserves earlier paired result on disk`() {
        val output = Files.createTempDirectory("tier-e-noise-partial-")
        try {
            assertThrows(IllegalStateException::class.java) {
                TierENodeNoiseProbe.runPairs(
                    2,
                    output,
                    observe = { TierENodeNoiseSample(10, 0.1, 0.1, 1.0) },
                    attached = { target -> if (target.fileName.toString().startsWith("target-2-")) error("second target failed") },
                )
            }
            assertEquals(1, Files.readAllLines(output.resolve("results.jsonl")).size)
            assertTrue(!Files.exists(output.resolve("summary.json")))
        } finally {
            output.toFile().deleteRecursively()
        }
    }

    @Test
    fun `attached PID binds to the sustained full stream variant with its scoped suffix`() {
        val rows = listOf(
            kotlinx.serialization.json.Json
                .parseToJsonElement(
            """{"kind":"fork-output","variant":"dictionary_full_stream_collector_continuous","output":"collector_pid=31 workload_pid=123 tier-e-perf-window: pid=31"}""",
        ).jsonObject,
        )
        assertTrue(TierENodeNoiseProbe.boundToFirstFullStream(rows, 123))
        assertTrue(!TierENodeNoiseProbe.boundToFirstFullStream(rows, 12))
    }

    @Test
    fun `observed readiness marker is separate from the changing target artifact`() {
        val root = Path.of("/tmp/tier-e-noise")
        val target = root.resolve("target-1-artifact")
        val observed = TierENodeNoiseProbe.observedMarker(root, target)
        assertTrue(observed.startsWith(root))
        assertTrue(!observed.startsWith(target))
        assertEquals(123L, TierENodeNoiseProbe.workloadPid("workload_pid=123\n"))
    }

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
