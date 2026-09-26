package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TierEBenchmarkOperationAcceptanceTest {
    private fun objectFrom(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `representative unique edge remains valid only with bound complete evidence`() {
        val observations = objectFrom("""{"representative_only":true,"unresolved_observations":0}""")
        val verdict =
            objectFrom(
                """{"operation_coverage_verdict":"INCOMPLETE","event_history_verdict":"NOT_APPLICABLE","recovered_operation_ids":1,"unresolved_observations":0,"terminal_evidence_integrity":"COMPLETE","policy_eligibility":false}""",
            )
        assertTrue(TierEBenchmarkOperationAcceptance.accept("unique-stack-syscall", observations, verdict))
        assertFalse(TierEBenchmarkOperationAcceptance.accept("full-stream", observations, verdict))
        assertFalse(
            TierEBenchmarkOperationAcceptance.accept(
                "unique-stack-syscall",
                observations,
            objectFrom(verdict.toString().replace("\"INCOMPLETE\"", "\"UNVERIFIED\"")),
            ),
        )
        assertFalse(
            TierEBenchmarkOperationAcceptance.accept(
                "unique-stack-syscall",
                observations,
            objectFrom(verdict.toString().replace("\"COMPLETE\"", "\"INCOMPLETE\"")),
            ),
        )
        assertFalse(
            TierEBenchmarkOperationAcceptance.accept(
                "unique-stack-syscall",
            objectFrom("""{"representative_only":false,"unresolved_observations":0}"""),
                verdict,
            ),
        )
    }
}
