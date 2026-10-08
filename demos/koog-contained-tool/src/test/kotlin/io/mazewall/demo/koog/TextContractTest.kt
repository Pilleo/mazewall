package io.mazewall.demo.koog

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextContractTest {
    @Test
    fun `application limit counts strict UTF8 bytes independently of wire allowance`() {
        TextBounds.requireBounded("x".repeat(65_536))
        TextBounds.requireBounded("é".repeat(32_768))
        assertFailsWith<IllegalArgumentException> { TextBounds.requireBounded("é".repeat(32_769)) }
        assertFailsWith<IllegalArgumentException> { TextBounds.requireBounded("\uD800") }
        assertEquals(65_536, TextBounds.MAX_TEXT_BYTES)
        assertEquals(131_072, TextTools.WIRE_BYTES)
    }
}
