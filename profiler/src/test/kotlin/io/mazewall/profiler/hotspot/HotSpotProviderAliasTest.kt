package io.mazewall.profiler.hotspot

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.profiler.api.HotSpotObservationTarget
import io.mazewall.profiler.internal.hotspot.HotSpotCaptureExpectation
import io.mazewall.profiler.internal.hotspot.HotSpotCollectorTiming
import io.mazewall.profiler.internal.hotspot.HotSpotProtocolException
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import io.mazewall.profiler.internal.hotspot.HotSpotSessionEvent
import io.mazewall.profiler.internal.hotspot.HotSpotSessionInterpreter
import io.mazewall.profiler.internal.hotspot.HotSpotWireMapper
import io.mazewall.profiler.internal.hotspot.HotSpotWireReader
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
class HotSpotProviderAliasTest {
    @Test
    fun `negotiated lifecycle rejects provider aliases`() = checkAliases(listOf(0, 1))

    @Test
    fun `negotiated enrichment rejects provider aliases`() = checkAliases(listOf(3))

    @Test
    fun `negotiated collector errors reject provider aliases`() = checkAliases(listOf(1), errorRow = true)

    private fun checkAliases(
        indices: List<Int>,
        errorRow: Boolean = false,
    ) {
        for (schema in listOf(2uL, 3uL)) {
            val rows = fixture(schema).let {
                if (errorRow) {
                    it.take(2).map { row ->
                        row
                            .replace("\"kind\":\"collector_ready\"", "\"kind\":\"collector_error\"")
                            .replace("\"lifecycle\":{", "\"lifecycle\":{\"message\":\"native failure\",")
                    }
                } else {
                    it
                }
            }
            consume(schema, rows)
            for (index in indices) {
                for (key in listOf("name", "gadget_revision", "profiler_revision", "patch_sha256")) {
                    val field = Regex("\"$key\":\"([^\"]+)\"").find(rows[index])!!.value
                    val value = field.substringAfter(':')
                    for (aliasValue in listOf(value, "\"conflicting\"")) {
                        val alias = "\"${key.uppercase()}\":$aliasValue"
                        for (replacement in listOf("$field,$alias", "$alias,$field")) {
                            val changed = rows.toMutableList().also { it[index] = it[index].replace(field, replacement) }
                            assertFailsWith<HotSpotProtocolException>("schema=$schema row=$index key=$key alias=$alias") {
                                consume(schema, changed)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `unknown provider extension and legacy replay preserve raw evidence`() {
        val extension = "\"future_patch_sha256\":\"not-a-pin\""
        for (schema in listOf(2uL, 3uL)) {
            val rows = fixture(schema).map { it.replace("\"provider\":{", "\"provider\":{$extension,") }
            consume(schema, rows)
            HotSpotWireReader(HotSpotReplayLimits(), schema).read((rows.joinToString("\n") + "\n").byteInputStream()) { row ->
                if (row.kind == "enrichment") {
                    assertEquals(rows[3], row.rawJson)
                    assertTrue(HotSpotWireMapper(HotSpotReplayLimits()).evidence(row).rawJson.contains(extension))
                }
            }
        }
        val legacy = fixture(2uL)[3]
            .replace("\"schema_version\":2", "\"schema_version\":1")
            .replace("\"provider\":{", "\"provider\":{\"NAME\":\"legacy\",")
        HotSpotWireReader(HotSpotReplayLimits(), 1uL).read("$legacy\n".byteInputStream()) { assertEquals(legacy, it.rawJson) }
    }

    private fun fixture(schema: ULong): List<String> {
        val rows = requireNotNull(javaClass.getResourceAsStream("/hotspot/observed-consumer.jsonl"))
            .bufferedReader()
            .use { it.readLines().take(4) }
        return if (schema == 3uL) {
            rows
        } else {
            rows.map {
                it
                    .replace("\"schema_version\":3,\"policy_eligibility\":false", "\"schema_version\":2")
                    .replace("otel_hotspot25_observed_v1", "temurin25_xint_serial")
            }
        }
    }

    private fun consume(
        schema: ULong,
        rows: List<String>,
    ) {
        val expectation = if (schema == 3uL) {
            HotSpotCaptureExpectation.Observed(HotSpotObservationTarget(Pid(123), 456uL, 789uL, 42uL))
        } else {
            HotSpotCaptureExpectation.Strict
        }
        val limits = HotSpotReplayLimits()
        val interpreter = HotSpotSessionInterpreter(123, 456uL, limits, HotSpotCollectorTiming(), expectation)
        interpreter.event(HotSpotSessionEvent.Start)
        HotSpotWireReader(limits, schema).read((rows.joinToString("\n") + "\n").byteInputStream()) { interpreter.record(it) }
    }
}
