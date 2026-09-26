package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierESustainedCapacityStageTest {
    @Test
    fun `complete coverage without performance eligibility cannot advance the pilot ceiling`() {
        val results = Files.createTempFile("tier-e-capacity-stage-", ".jsonl")
        try {
            Files.writeString(
                results,
                """{"kind":"fork-output","sustained_collector":{"evidence_integrity":"COMPLETE","performance_eligible":true,"retained_state_high_water_bytes":500}}
{"kind":"terminal","success":true}
""",
            )
            assertTrue(TierESustainedCapacityStage.eligible(results, 1000))
            Files.writeString(results, Files.readString(results).replace("\"performance_eligible\":true", "\"performance_eligible\":false"))
            assertFalse(TierESustainedCapacityStage.eligible(results, 1000))
            Files.writeString(
                results,
                Files
                    .readString(results)
                    .replace("\"performance_eligible\":false", "\"performance_eligible\":true")
                .replace("\"retained_state_high_water_bytes\":500", "\"retained_state_high_water_bytes\":751"),
            )
            assertFalse(TierESustainedCapacityStage.eligible(results, 1000))
        } finally {
            Files.deleteIfExists(results)
        }
    }
}
