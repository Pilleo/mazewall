package io.mazewall.profiler.hotspot

import io.mazewall.profiler.internal.hotspot.HotSpotProtocolException
import io.mazewall.profiler.internal.hotspot.HotSpotReplayLimits
import io.mazewall.profiler.internal.hotspot.HotSpotWireReader
import org.junit.jupiter.api.Test
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HotSpotWireReaderTest {
    @Test
    fun `valid prefix is delivered but malformed second row still aborts`() {
        val first = """{"schema_version":1,"kind":"collector_attached","session_id":"s"}""" + "\n"
        var delivered = 0
        assertFailsWith<HotSpotProtocolException> {
            HotSpotWireReader(HotSpotReplayLimits()).read((first.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28, 0x0a)).inputStream()) { delivered++ }
        }
        assertEquals(1, delivered)
    }

    @Test
    fun `split multibyte rows and independent reads preserve decoder boundaries`() {
        val row = """{"schema_version":1,"kind":"collector_attached","session_id":"s","name":"λ猫"}""" + "\n"
        val bytes = (row + row).toByteArray()
        val reader = HotSpotWireReader(HotSpotReplayLimits())
        repeat(2) {
            var offset = 0
            val split = object : InputStream() {
                override fun read(): Int = error("bounded block reads required")

                override fun read(
                    buffer: ByteArray,
                    start: Int,
                    length: Int,
                ): Int {
                    if (offset == bytes.size) return -1
                    val count = minOf(length, 13, bytes.size - offset)
                    bytes.copyInto(buffer, start, offset, offset + count)
                    offset += count
                    return count
                }
            }
            var rows = 0
            reader.read(split) {
                assertEquals(row.trim(), it.rawJson)
                rows++
            }
            assertEquals(2, rows)
        }
    }

    @Test
    fun `pipe input is read in bounded blocks while split records retain newline framing`() {
        val text = """{"schema_version":1,"kind":"collector_attached","session_id":"s"}""" + "\n"
        val source = (text + text).toByteArray()
        var offset = 0
        val pipe = object : InputStream() {
            override fun read(): Int = error("scalar pipe reads are too expensive for interpreter-mode supervision")

            override fun read(
                buffer: ByteArray,
                start: Int,
                length: Int,
            ): Int {
                if (offset == source.size) return -1
                val count = minOf(length, 13, source.size - offset)
                source.copyInto(buffer, start, offset, offset + count)
                offset += count
                return count
            }
        }
        var records = 0
        HotSpotWireReader(HotSpotReplayLimits()).read(pipe) { records++ }
        assertEquals(2, records)
    }

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
