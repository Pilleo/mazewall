package io.mazewall.portal

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    fun `remote failures retain type and bounded message without serializing a throwable`() {
        val encoded =
            PortalRemoteFailureCodec.encode(
                PortalRemoteFailure("fixture.InvalidDocument", "unsupported page header"),
            )

        assertEquals(
            PortalRemoteFailure("fixture.InvalidDocument", "unsupported page header"),
            PortalRemoteFailureCodec.decode(encoded),
        )
    }

    @Test
    fun `remote failure payload rejects trailing bytes`() {
        val encoded = PortalRemoteFailureCodec.encode(PortalRemoteFailure("fixture.Invalid", "no")) + byteArrayOf(1)

        assertThrows(IllegalArgumentException::class.java) { PortalRemoteFailureCodec.decode(encoded) }
    }
}
