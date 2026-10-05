package io.mazewall.profiler.hotspot

import io.mazewall.profiler.internal.hotspot.HotSpotEvidenceAssembler
import io.mazewall.profiler.internal.hotspot.HotSpotProtocolException
import io.mazewall.profiler.internal.hotspot.HotSpotRawJson
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import io.mazewall.profiler.internal.hotspot.HotSpotWireMapper
import io.mazewall.profiler.internal.hotspot.HotSpotWireReader
import io.mazewall.profiler.internal.hotspot.HotSpotWireRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HotSpotRawJsonTest {
    @Test
    fun `raw rendering is deferred and canonical unknown fields survive`() {
        var renders = 0
        val tree = Json.parseToJsonElement("""{"unknown":{"quoted":"a\\\"b"},"n":18446744073709551615}""")
        val raw = HotSpotRawJson(tree) {
            renders++
            it.toString()
        }
        assertEquals(0, renders)
        assertEquals(tree.toString(), raw.text)
        assertEquals(tree.toString(), raw.text)
        assertEquals(1, renders)
    }

    @Test
    fun `concurrent diagnostic readers see the same canonical immutable tree`() {
        val tree = Json.parseToJsonElement("""{"utf8":"λ猫","unknown":[1,2,3]}""")
        val raw = HotSpotRawJson(tree)
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val readers = List(2) {
                pool.submit<String> {
                start.await()
                raw.text
            }
            }
            start.countDown()
            readers.forEach { assertEquals(tree.toString(), it.get(5, TimeUnit.SECONDS)) }
        }
    }

    @Test
    fun `mapped event evidence and frame diagnostics match eager canonical values`() {
        val rows = mutableListOf<HotSpotWireRecord>()
        HotSpotWireReader(HotSpotReplayLimits()).read(projection().byteInputStream(), rows::add)
        val mapper = HotSpotWireMapper(HotSpotReplayLimits())
        assertEquals(rows[0].objectValue.getValue("event").toString(), mapper.event(rows[0]).rawJson)
        val evidence = mapper.evidence(rows[1])
        val raw = rows[1].objectValue.getValue("evidence").jsonObject
        assertEquals(raw.toString(), evidence.rawJson)
        assertEquals(raw.getValue("frames").jsonArray.map { it.toString() }, evidence.frames.map { it.rawJson })
    }

    @Test
    fun `zero retention still validates invalid typed frame fields`() {
        val invalid = projection().replace("\"provider_type\":5", "\"provider_type\":\"5\"")
        assertFailsWith<HotSpotProtocolException> {
            HotSpotEvidenceAssembler(HotSpotReplayLimits(maxRetainedOccurrences = 0)).read(invalid.byteInputStream())
        }
    }

    private fun projection(): String = checkNotNull(javaClass.getResourceAsStream("/hotspot/measured-event-projection.jsonl")).bufferedReader().use { it.readText() }
}
