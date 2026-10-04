package io.mazewall.profiler.hotspot

import io.mazewall.profiler.internal.hotspot.HotSpotProtocolException
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import io.mazewall.profiler.internal.hotspot.HotSpotWireReader
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class HotSpotWireReaderTest {
    @Test
    fun `excessive nesting is rejected before recursive JSON allocation`() {
        val nested = "[".repeat(65) + "0" + "]".repeat(65)
        val row = """{"schema_version":1,"kind":"collector_attached","session_id":"s","extra":$nested}""" + "\n"
        assertFailsWith<HotSpotProtocolException> { HotSpotWireReader(HotSpotReplayLimits()).read(row.byteInputStream()) {} }
    }

    @Test
    fun `malformed UTF8 and newline-truncated input are explicit protocol failures`() {
        assertFailsWith<HotSpotProtocolException> { HotSpotWireReader(HotSpotReplayLimits()).read(byteArrayOf(0xc3.toByte(), 0x28, 0x0a).inputStream()) {} }
        assertFailsWith<HotSpotProtocolException> { HotSpotWireReader(HotSpotReplayLimits()).read("{}".byteInputStream()) {} }
    }
}
