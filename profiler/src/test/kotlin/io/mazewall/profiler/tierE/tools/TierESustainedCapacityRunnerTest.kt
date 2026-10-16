package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierESustainedCapacityRunnerTest {
    @Test
    fun `first ineligible stage freezes the prior eligible ceiling`() {
        val output = Files.createTempDirectory("tier-e-capacity-runner-")
        try {
            val settings = TierESustainedCapacitySettings.load(
                mapOf(
                "MAZEWALL_TIER_E_SUSTAINED_PILOT_RATES" to "1000 2000",
                "MAZEWALL_TIER_E_SUSTAINED_STAGE_SECONDS" to "1",
                "MAZEWALL_TIER_E_SUSTAINED_ITERATIONS" to "100",
                "MAZEWALL_TIER_E_SUSTAINED_SHADOW_ON_FAILURE" to "false",
            ),
            )
            TierESustainedCapacityRunner.run(settings, output) { stage, directory, _ ->
                Files.createDirectories(directory)
                Files.writeString(
                    directory.resolve("results.jsonl"),
                    """{"kind":"fork-output","sustained_collector":{"evidence_integrity":"COMPLETE","performance_eligible":${stage.rate == 1000},"retained_state_high_water_bytes":1}}
{"kind":"terminal","success":true}
""",
                )
            }
            val manifest = Json.parseToJsonElement(Files.readString(output.resolve("capacity-manifest.json"))).jsonObject
            assertEquals("1000", manifest.getValue("loss_free_ceiling_rate_per_second").toString())
            assertEquals(
                "true",
                Json
                .parseToJsonElement(Files.readString(output.resolve("terminal.json")))
                .jsonObject
                .getValue("success")
                .toString(),
            )
        } finally {
            output.toFile().deleteRecursively()
        }
    }
}
