package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class SupervisorValidationDecoderTest {
    @Test
    fun `rejects invalid handshake before a verdict can be read`() {
        assertInstanceOf(
            SupervisorValidationDecodeResult.ProtocolViolation::class.java,
            SupervisorValidationDecoder.handshake(0x7f),
        )
    }

    @Test
    fun `rejects negative string length without allocating`() {
        val input = DataInputStream(ByteArrayInputStream(byteArrayOf(1, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())))

        val result = SupervisorValidationDecoder.arguments(input, 1)

        assertInstanceOf(SupervisorValidationDecodeResult.ProtocolViolation::class.java, result)
    }

    @Test
    fun `rejects unknown argument tag`() {
        val input = DataInputStream(ByteArrayInputStream(byteArrayOf(99)))

        val result = SupervisorValidationDecoder.arguments(input, 1)

        assertInstanceOf(SupervisorValidationDecodeResult.ProtocolViolation::class.java, result)
    }

    @Test
    fun `decodes bounded arguments`() {
        val bytes = byteArrayOf(
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            7,
            1,
            0,
            0,
            0,
            2,
            'o'.code.toByte(),
            'k'.code.toByte(),
        )

        val result = SupervisorValidationDecoder.arguments(DataInputStream(ByteArrayInputStream(bytes)), 2)

        val decoded = assertInstanceOf(SupervisorValidationDecodeResult.Arguments::class.java, result)
        assertEquals(listOf(7L, "ok"), decoded.values)
        assertTrue(decoded.values.none { it is ByteArray })
    }
}
