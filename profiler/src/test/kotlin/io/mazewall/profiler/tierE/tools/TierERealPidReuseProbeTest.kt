package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration

class TierERealPidReuseProbeTest {
    @Test
    fun `real replacement admission requires the exact observed PID and live start time`() {
        val result = Json
            .parseToJsonElement(
            """{"original":{"pid":3514,"starttime":133817},"replacement":{"pid":3514,"starttime":134936},"host_pid_reuse_verdict":"OBSERVED","policy_eligibility":false}""",
        ).jsonObject
        assertTrue(TierERealPidReuseProbe.validReplacement(result, 3514, 133817, 134936))
        assertFalse(TierERealPidReuseProbe.validReplacement(result, 3514, 133817, 134937))
        assertFalse(TierERealPidReuseProbe.validReplacement(result, 3515, 133817, 134936))
    }

    @Test
    fun `proc stat parser counts fields after final process-name parenthesis`() {
        val line = "123 (name ) with parentheses) " + (1..20).joinToString(" ")
        assertEquals(20L, TierERealPidReuseProbe.starttime(line))
        assertThrows(IllegalArgumentException::class.java) { TierERealPidReuseProbe.starttime("123 (bad) 1") }
    }

    @Test
    fun `bounded host PID probe publishes an ineligible result and reaps children`() {
        val output = Files.createTempFile("tier-e-real-pid-", ".json")
        try {
            val result = TierERealPidReuseProbe.run(
                registeredPid = ProcessHandle.current().pid(),
                registeredStarttime = TierERealPidReuseProbe.starttime(
                    Files.readString(
                        java.nio.file.Path
                    .of("/proc/self/stat"),
                    ),
                ),
                attempts = 2,
                deadline = Duration.ofSeconds(2),
                hold = Duration.ZERO,
                output = output,
            )
            assertEquals("UNVERIFIED", result.getValue("host_pid_reuse_verdict").toString().trim('"'))
            assertTrue(Files.exists(output))
            assertEquals(
                "false",
                Json
                .parseToJsonElement(Files.readString(output))
                .jsonObject
                .getValue("policy_eligibility")
                .toString(),
            )
        } finally {
            Files.deleteIfExists(output)
        }
    }
}
