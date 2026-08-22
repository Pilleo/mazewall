package io.mazewall.portal

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PortalCodecTest {
    @Test
    fun `string and int round trip`() {
        val payload =
            PortalCodec.concat(
                listOf(
                    PortalCodec.encodeInt(42),
                    PortalCodec.encodeString("portal"),
                    PortalCodec.encodeBytes(byteArrayOf(1, 2)),
                ),
            )
        val reader = PortalCodec.Reader(payload)
        assertEquals(42, reader.int())
        assertEquals("portal", reader.string())
        assertArrayEquals(byteArrayOf(1, 2), reader.bytes())
    }
}
