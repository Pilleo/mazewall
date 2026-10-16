package io.mazewall.profiler.triage
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DiagnosticReportTest {
    @Test
    fun `diagnostic values round trip all control characters and hostile quotes`() {
        val value = (0..31).map(Int::toChar).joinToString("") + "\"\\\u20ac"
        val report = diagnosticReportJson(17, mapOf("hostile\"key" to value))
        val parsed = Json.parseToJsonElement(report).jsonObject
        assertEquals("17", parsed.getValue("timestamp").jsonPrimitive.content)
        assertEquals(
            value,
            parsed
            .getValue("diagnostics")
            .jsonObject
            .getValue("hostile\"key")
            .jsonPrimitive.content,
        )
    }
}
