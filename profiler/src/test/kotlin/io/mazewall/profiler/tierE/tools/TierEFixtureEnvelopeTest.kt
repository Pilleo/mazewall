package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TierEFixtureEnvelopeTest {
    @Test
    fun `negative envelopes preserve little endian v2 header and session distinction`() {
        val unclosed = ByteBuffer.wrap(TierEFixtureEnvelope.create("unclosed")).order(ByteOrder.LITTLE_ENDIAN)
        val mismatch = ByteBuffer.wrap(TierEFixtureEnvelope.create("session-mismatch")).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("MZSE", String(unclosed.array().copyOfRange(0, 4)))
        assertEquals(2, unclosed.getShort(4).toInt())
        assertEquals(0, unclosed.getShort(6).toInt())
        assertEquals(1, mismatch.getShort(6).toInt())
        assertEquals(0x4d5aL, unclosed.getLong(8))
        assertEquals(0x4d5bL, mismatch.getLong(8))
        assertEquals(6L, mismatch.getLong(32))
        assertEquals(46, mismatch.array().size)
        assertThrows(IllegalArgumentException::class.java) { TierEFixtureEnvelope.create("normal") }
    }
}
