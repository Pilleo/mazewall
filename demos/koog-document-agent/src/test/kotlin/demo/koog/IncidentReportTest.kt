package demo.koog

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IncidentReportTest {
    private val reads = mapOf("application.log" to "service started\npool active=2 idle=0 waiting=18")
    private val valid = """{"hypothesis":"Pool capacity was reduced.","evidence":[{"document":"application.log","line":2}],"nextSteps":["Review the pool setting."]}"""

    @Test fun `renders the actual validated model result rather than a canned diagnosis`() {
        val report = decodeIncidentReport(valid, reads)
        assertEquals("Pool capacity was reduced.", report.hypothesis)
        assertEquals(EvidenceRef("application.log", 2), report.evidence.single())
        val rendered = renderIncidentReport(report)
        assertTrue(rendered.contains("## Hypothesis\nPool capacity was reduced."))
        assertTrue(rendered.contains("application.log:2"))
        assertTrue(rendered.contains("Review the pool setting."))
        assertNotEquals(rendered, renderIncidentReport(decodeIncidentReport(valid.replace("Pool capacity was reduced.", "The endpoint was changed."), reads)))
    }

    @Test fun `cannot cite an unread document or a nonexistent line`() {
        for (bad in listOf(valid.replace("application.log", "deployment.md"), valid.replace("\"line\":2", "\"line\":0"), valid.replace("\"line\":2", "\"line\":3"))) {
            assertFailsWith<IllegalArgumentException> { decodeIncidentReport(bad, reads) }
        }
        assertFailsWith<IllegalArgumentException> { decodeIncidentReport(valid, emptyMap()) }
    }

    @Test fun `rejects malformed excessive or unknown report fields`() {
        for (bad in listOf("{}", valid.dropLast(1), "\uD800", " ".repeat(16_385), valid.replace("\"hypothesis\":", "\"extra\":true,\"hypothesis\":"))) {
            assertFailsWith<IllegalArgumentException> { decodeIncidentReport(bad, reads) }
        }
    }

    @Test fun `documents have exact byte line and name bounds`() {
        validateIncidentDocument("application.log", "é".repeat(4096))
        validateIncidentDocument("deployment.md", List(256) { "line" }.joinToString("\n"))
        for ((name, text) in listOf("private.txt" to "text", "application.log" to "é".repeat(4097), "application.log" to "\uD800", "deployment.md" to List(257) { "line" }.joinToString("\n"))) {
            assertFailsWith<IllegalArgumentException> { validateIncidentDocument(name, text) }
        }
    }

    @Test fun `report cardinalities and content are bounded`() {
        for (bad in listOf(
            valid.replace("Pool capacity was reduced.", " "),
            valid.replace("[{\"document\":\"application.log\",\"line\":2}]", "[]"),
            valid.replace("[{\"document\":\"application.log\",\"line\":2}]", List(9) { """{"document":"application.log","line":2}""" }.joinToString(",", "[", "]")),
            valid.replace("[\"Review the pool setting.\"]", "[]"),
            valid.replace("[\"Review the pool setting.\"]", List(6) { "\"step\"" }.joinToString(",", "[", "]")),
        )) {
            assertFailsWith<IllegalArgumentException> { decodeIncidentReport(bad, reads) }
        }
    }

    @Test fun `terminal control characters cannot disguise the displayed result`() {
        assertFailsWith<IllegalArgumentException> { decodeIncidentReport(valid.replace("Pool capacity was reduced.", "\\u001b[2Jhidden"), reads) }
    }

    @Test fun `decoded display strings must preserve valid Unicode rather than replace escaped lone surrogates`() {
        for (field in listOf("Pool capacity was reduced.", "Review the pool setting.")) {
            for (invalid in listOf("\\uD800", "\\uDC00")) {
                assertFailsWith<IllegalArgumentException> { decodeIncidentReport(valid.replace(field, invalid), reads) }
            }
            val report = decodeIncidentReport(valid.replace(field, "\\uD83D\\uDE42"), reads)
            assertTrue(renderIncidentReport(report).contains("🙂"))
        }
    }
}
