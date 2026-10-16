package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierECoverageSummaryTest {
    @Test
    fun `coverage pass cannot upgrade incomplete event history`() {
        val rows = TierECoverageSummary.parse(
            """{"kind":"metadata","forks":1}
{"kind":"fork-output","variant":"dictionary_full_stream","fork":1,"whole_session_evidence":"INCOMPLETE","workload_correctness":"FAILED","lost":3}
{"kind":"coverage","variant":"dictionary_full_stream","fork":1,"report":{"coverage_verdict":"PASS","recovered":[0]}}
{"kind":"terminal","success":true}
""",
        )
        val summary = TierECoverageSummary.summarize(rows)
        val arm = summary
            .getValue("variants")
            .jsonArray[0]
            .jsonObject
            .getValue("forks")
            .jsonArray[0]
            .jsonObject
        assertEquals("\"PASS\"", arm.getValue("coverage_verdict").toString())
        assertEquals("\"INCOMPLETE\"", arm.getValue("whole_session_evidence").toString())
        assertEquals(
            "\"INCOMPLETE\"",
            arm
            .getValue("dimensions")
            .jsonObject
            .getValue("transport_completeness")
            .toString(),
        )
        assertEquals("false", summary.getValue("policy_eligibility").toString())
    }

    @Test
    fun `torn final JSONL retains prefix but cannot complete the run`() {
        val rows = TierECoverageSummary.parse(
            """{"kind":"metadata","forks":1}
{"kind":"fork-output"""",
        )
        val summary = TierECoverageSummary.summarize(rows)
        assertEquals("false", summary.getValue("run_completed").toString())
        assertEquals(
            "\"interrupted_jsonl_tail\"",
            summary
            .getValue("failures")
            .jsonArray[0]
            .jsonObject
            .getValue("reason")
            .toString(),
        )
        assertThrows(Exception::class.java) {
            TierECoverageSummary.parse("{broken}\n{\"kind\":\"metadata\"}\n")
        }
    }
}
